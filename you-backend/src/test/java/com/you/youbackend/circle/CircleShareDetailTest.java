package com.you.youbackend.circle;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.album.model.AlbumOrganizeMode;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.circle.controller.CircleController;
import com.you.youbackend.circle.entity.Circle;
import com.you.youbackend.circle.entity.CircleShare;
import com.you.youbackend.circle.mapper.CircleMapper;
import com.you.youbackend.circle.mapper.CircleMemberMapper;
import com.you.youbackend.circle.mapper.CircleShareMapper;
import com.you.youbackend.circle.model.CircleShareObjectType;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.entity.MomentImage;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.mapper.MomentImageMapper;
import com.you.youbackend.moment.mapper.MomentTagMapper;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.filter.JwtAuthFilter;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 三个圈子共享详情的访问边界、真实内容组装和 MVC 路由测试；持久化与签名使用替身。 */
class CircleShareDetailTest {
    private final CircleMapper circles = mock(CircleMapper.class);
    private final CircleMemberMapper members = mock(CircleMemberMapper.class);
    private final CircleShareMapper shares = mock(CircleShareMapper.class);
    private final MomentMapper moments = mock(MomentMapper.class);
    private final AlbumMapper albums = mock(AlbumMapper.class);
    private final AlbumMomentMapper links = mock(AlbumMomentMapper.class);
    private final MomentImageMapper images = mock(MomentImageMapper.class);
    private final MomentTagMapper tags = mock(MomentTagMapper.class);
    private final MediaService media = mock(MediaService.class);
    private final UserService users = mock(UserService.class);
    private final MomentService momentService = new MomentService(moments, images, tags, media,
            mock(GatheringService.class), users);
    private final AlbumService albumService = new AlbumService(albums, links, moments, media, momentService);
    private final CircleService service = new CircleService(circles, members, users, media, shares, moments,
            albums, links, momentService, albumService, "defaults/circle-avatar.png");
    private final UUID circleId = id(), shareId = id(), viewer = id(), owner = id();
    private final UUID momentId = id(), albumId = id();
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 14, 10, 0);

    @Test
    void readsOtherMembersMomentAndSignsItsImageAfterAuthorization() {
        accessibleShare(CircleShareObjectType.moment);
        Moment moment = moment(owner);
        when(moments.selectById(momentId)).thenReturn(moment);
        UUID fileId = id();
        MomentImage image = new MomentImage();
        image.setMomentId(momentId);
        image.setFileId(fileId);
        when(images.selectFeedImages(List.of(momentId))).thenReturn(List.of(image));
        when(media.findImageKeys(List.of(fileId))).thenReturn(Map.of(fileId, "owner/photo"));
        when(media.createReadUrl("owner/photo")).thenReturn("signed-photo");
        var profile = new PublicUserResponse(owner, "分享者", null);
        when(users.getPublicUsers(List.of(owner))).thenReturn(Map.of(owner, profile));

        var result = service.getSharedMoment(circleId, shareId, viewer);

        assertEquals(momentId, result.id());
        assertEquals("完整正文", result.body());
        assertEquals("signed-photo", result.images().get(0).url());
        assertSame(profile, result.author());
        var order = inOrder(members, moments, images);
        order.verify(members).isActiveMember(circleId, viewer);
        order.verify(members).isActiveMember(circleId, owner);
        order.verify(moments).selectById(momentId);
        order.verify(images).selectFeedImages(List.of(momentId));
        verify(moments, times(1)).selectById(momentId);
    }

    @Test
    void albumUsesOwnersCountAndCoverInsteadOfViewersFiles() {
        accessibleShare(CircleShareObjectType.album);
        Album album = album();
        UUID coverId = id();
        album.setCoverFileId(coverId);
        when(albums.selectById(albumId)).thenReturn(album);
        when(links.countValidMoments(albumId, owner)).thenReturn(2L);
        when(media.findOwnedImageKeys(owner, List.of(coverId))).thenReturn(Map.of(coverId, "owner/cover"));
        when(media.createReadUrl("owner/cover")).thenReturn("signed-cover");

        var result = service.getSharedAlbum(circleId, shareId, viewer);

        assertEquals(albumId, result.id());
        assertEquals(2, result.momentCount());
        assertEquals(coverId, result.coverFileId());
        assertEquals("signed-cover", result.coverUrl());
        verify(albums, times(1)).selectById(albumId);
        verify(media, never()).findOwnedImageKeys(eq(viewer), anyCollection());
    }

    @Test
    void readsEmptyAlbumWithoutCoverAndKeepsNullableFields() {
        accessibleShare(CircleShareObjectType.album);
        when(albums.selectById(albumId)).thenReturn(album());

        var result = service.getSharedAlbum(circleId, shareId, viewer);

        assertEquals(0, result.momentCount());
        assertNull(result.coverFileId());
        assertNull(result.coverUrl());
        assertNull(result.description());
        verifyNoInteractions(media);
    }

    @Test
    void readsMomentThroughSharedAlbumWithoutDirectMomentShare() {
        accessibleShare(CircleShareObjectType.album);
        when(albums.selectById(albumId)).thenReturn(album());
        when(links.hasActiveAssociation(albumId, momentId)).thenReturn(true);
        when(moments.selectById(momentId)).thenReturn(moment(owner));

        var result = service.getSharedAlbumMoment(circleId, shareId, momentId, viewer);

        assertEquals(momentId, result.id());
        assertNull(result.author());
        verify(shares, times(1)).selectHistoryById(circleId, shareId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"circleMissing", "viewerLeft"})
    void rejectsInaccessibleCircleBeforeLoadingShare(String scenario) {
        if (scenario.equals("viewerLeft")) when(circles.selectById(circleId)).thenReturn(new Circle());

        assertStatus(404, () -> service.getSharedMoment(circleId, shareId, viewer));
        assertStatus(404, () -> service.getSharedAlbum(circleId, shareId, viewer));
        assertStatus(404, () -> service.getSharedAlbumMoment(circleId, shareId, momentId, viewer));
        verifyNoInteractions(shares, moments, albums, links, images, tags, media, users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "withdrawn", "sharerLeft"})
    void rejectsUnavailableShareBeforeReadingOriginalContent(String scenario) {
        CircleShare share = accessibleShare(CircleShareObjectType.moment);
        switch (scenario) {
            case "missing" -> when(shares.selectHistoryById(circleId, shareId)).thenReturn(null);
            case "withdrawn" -> share.setWithdrawnAt(now);
            case "sharerLeft" -> when(members.isActiveMember(circleId, owner)).thenReturn(false);
            default -> throw new IllegalArgumentException(scenario);
        }

        assertStatus(404, () -> service.getSharedMoment(circleId, shareId, viewer));
        assertStatus(404, () -> service.getSharedAlbum(circleId, shareId, viewer));
        assertStatus(404, () -> service.getSharedAlbumMoment(circleId, shareId, momentId, viewer));
        verifyNoInteractions(moments, albums, links, images, tags, media, users);
    }

    @Test
    void shareFromAnotherCircleIsUnavailableEvenForItsOwner() {
        accessibleShare(CircleShareObjectType.moment);
        UUID otherCircle = id();
        when(circles.selectById(otherCircle)).thenReturn(new Circle());
        when(members.isActiveMember(otherCircle, owner)).thenReturn(true);

        assertStatus(404, () -> service.getSharedMoment(otherCircle, shareId, owner));
        verify(shares).selectHistoryById(otherCircle, shareId);
        verifyNoInteractions(moments, images, tags, media, users);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsWrongShareTypeBeforeReadingContent(boolean albumShare) {
        accessibleShare(albumShare ? CircleShareObjectType.album : CircleShareObjectType.moment);

        if (albumShare) {
            assertStatus(409, () -> service.getSharedMoment(circleId, shareId, viewer));
        } else {
            assertStatus(409, () -> service.getSharedAlbum(circleId, shareId, viewer));
            assertStatus(409, () -> service.getSharedAlbumMoment(circleId, shareId, momentId, viewer));
        }
        verifyNoInteractions(moments, albums, links, images, tags, media, users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"momentMissing", "momentOtherOwner", "albumMissing", "albumOtherOwner"})
    void rejectsDeletedOrMismatchedOriginalContent(String scenario) {
        boolean albumShare = scenario.startsWith("album");
        accessibleShare(albumShare ? CircleShareObjectType.album : CircleShareObjectType.moment);
        if (scenario.equals("momentOtherOwner")) when(moments.selectById(momentId)).thenReturn(moment(viewer));
        if (scenario.equals("albumOtherOwner")) {
            Album album = album();
            album.setOwnerUserId(viewer);
            when(albums.selectById(albumId)).thenReturn(album);
        }

        assertStatus(404, () -> {
            if (albumShare) service.getSharedAlbum(circleId, shareId, viewer);
            else service.getSharedMoment(circleId, shareId, viewer);
        });
        verifyNoInteractions(links, images, tags, media, users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unlinked", "missingMoment", "otherAuthor"})
    void rejectsRemovedDeletedOrForeignAlbumMoment(String scenario) {
        accessibleShare(CircleShareObjectType.album);
        when(albums.selectById(albumId)).thenReturn(album());
        when(links.hasActiveAssociation(albumId, momentId)).thenReturn(!scenario.equals("unlinked"));
        if (scenario.equals("otherAuthor")) when(moments.selectById(momentId)).thenReturn(moment(viewer));

        assertStatus(404, () -> service.getSharedAlbumMoment(circleId, shareId, momentId, viewer));
        verifyNoInteractions(images, tags, media, users);
        if (scenario.equals("unlinked")) verifyNoInteractions(moments);
    }

    @Test
    void mvcRoutesReturnDetailsInUnifiedResult() throws Exception {
        MockMvc mvc = mvc();
        accessibleShare(CircleShareObjectType.moment);
        when(moments.selectById(momentId)).thenReturn(moment(owner));
        mvc.perform(get("/circles/{circleId}/shares/{shareId}/moment", circleId, shareId)
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value("SUCCESS"))
                .andExpect(jsonPath("data.body").value("完整正文"));

        accessibleShare(CircleShareObjectType.album);
        when(albums.selectById(albumId)).thenReturn(album());
        when(links.hasActiveAssociation(albumId, momentId)).thenReturn(true);
        mvc.perform(get("/circles/{circleId}/shares/{shareId}/album", circleId, shareId)
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("data.id").value(albumId.toString()))
                .andExpect(jsonPath("data.momentCount").value(0));
        mvc.perform(get("/circles/{circleId}/shares/{shareId}/moments/{momentId}", circleId, shareId, momentId)
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("data.id").value(momentId.toString()));
    }

    @Test
    void mvcRejectsInvalidIdsAndMapsUnavailableShare() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(get("/circles/invalid/shares/{shareId}/moment", shareId)
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, viewer))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/circles/{circleId}/shares/{shareId}/album", circleId, UUID.randomUUID())
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, viewer))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(circles);
        mvc.perform(get("/circles/{circleId}/shares/{shareId}/moment", circleId, shareId)
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, viewer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("CIRCLE_NOT_FOUND"));
    }

    /** 模拟当前查看者和分享者均在圈子内，返回可按场景调整的有效分享。 */
    private CircleShare accessibleShare(CircleShareObjectType type) {
        when(circles.selectById(circleId)).thenReturn(new Circle());
        when(members.isActiveMember(circleId, viewer)).thenReturn(true);
        when(members.isActiveMember(circleId, owner)).thenReturn(true);
        CircleShare share = new CircleShare();
        share.setId(shareId);
        share.setCircleId(circleId);
        share.setSharerUserId(owner);
        share.setObjectType(type);
        share.setObjectId(type == CircleShareObjectType.moment ? momentId : albumId);
        when(shares.selectHistoryById(circleId, shareId)).thenReturn(share);
        return share;
    }

    /** 构造完整瞬间，保证响应转换所需的 UTC 时间已存在。 */
    private Moment moment(UUID author) {
        Moment moment = new Moment();
        moment.setId(momentId);
        moment.setAuthorUserId(author);
        moment.setBody("完整正文");
        moment.setMemoryTime(now);
        moment.setCreatedAt(now);
        moment.setUpdatedAt(now);
        return moment;
    }

    /** 构造手动时光，允许无介绍、封面和内部瞬间。 */
    private Album album() {
        Album album = new Album();
        album.setId(albumId);
        album.setOwnerUserId(owner);
        album.setName("旅行");
        album.setOrganizeMode(AlbumOrganizeMode.manual);
        album.setCreatedAt(now);
        album.setUpdatedAt(now);
        return album;
    }

    /** 使用实际 Controller、校验器和异常处理，仅将持久化及外部服务替换为测试对象。 */
    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new CircleController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    /** 检查业务错误的 HTTP 状态，避免把失效来源作为成功结果返回。 */
    private void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(BusinessException.class, action::run).getStatus().value());
    }

    private static UUID id() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
