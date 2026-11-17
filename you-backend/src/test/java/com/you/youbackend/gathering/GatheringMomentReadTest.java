package com.you.youbackend.gathering;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.request.PageQueryArgumentResolver;
import com.you.youbackend.gathering.controller.GatheringController;
import com.you.youbackend.gathering.model.GatheringMomentCursor;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.gathering.service.GatheringFaceToFaceService;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.entity.MomentImage;
import com.you.youbackend.moment.mapper.*;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.service.UserService;
import com.you.youbackend.user.response.PublicUserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 同行读取权限、跨作者图片、批量资料加载与 MVC 分页绑定。 */
class GatheringMomentReadTest {
    final MomentMapper moments = mock(MomentMapper.class);
    final MomentImageMapper images = mock(MomentImageMapper.class);
    final MomentTagMapper tags = mock(MomentTagMapper.class);
    final MediaService media = mock(MediaService.class);
    final GatheringService gatherings = mock(GatheringService.class);
    final UserService users = mock(UserService.class);
    final MomentService service = new MomentService(moments, images, tags, media, gatherings, users);
    final UUID viewer = UuidCreator.getTimeOrderedEpoch(), author = UuidCreator.getTimeOrderedEpoch();
    final UUID gatheringId = UuidCreator.getTimeOrderedEpoch();

    @Test
    void detailReadsAnotherAuthorsImageAfterMembershipCheck() {
        Moment moment = moment();
        UUID fileId = UuidCreator.getTimeOrderedEpoch();
        MomentImage image = new MomentImage(); image.setMomentId(moment.getId()); image.setFileId(fileId);
        var profile = new PublicUserResponse(author, "作者", null);
        when(moments.selectById(moment.getId())).thenReturn(moment);
        when(images.selectFeedImages(List.of(moment.getId()))).thenReturn(List.of(image));
        when(media.findImageKeys(List.of(fileId))).thenReturn(Map.of(fileId, "other-user/image"));
        when(media.createReadUrl("other-user/image")).thenReturn("signed-url");
        when(users.getPublicUsers(List.of(author))).thenReturn(Map.of(author, profile));
        var response = service.getGatheringMoment(gatheringId, moment.getId(), viewer);
        assertEquals("signed-url", response.images().get(0).url());
        assertSame(profile, response.author());
        var order = inOrder(gatherings, moments, images);
        order.verify(gatherings).requireMemberGathering(gatheringId, viewer);
        order.verify(moments).selectById(moment.getId());
        order.verify(images).selectFeedImages(List.of(moment.getId()));
        verify(media, never()).findOwnedImageKeys(any(), anyCollection());
    }

    @Test
    void missingOrUnlinkedDetailFailsBeforeLoadingFilesEvenForAuthor() {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        assertThrows(BusinessException.class, () -> service.getGatheringMoment(gatheringId, id, author));
        Moment moment = moment(); moment.setGatheringId(null);
        when(moments.selectById(moment.getId())).thenReturn(moment);
        assertEquals("MOMENT_NOT_FOUND", assertThrows(BusinessException.class,
                () -> service.getGatheringMoment(gatheringId, moment.getId(), author)).getCode());
        verifyNoInteractions(images, tags, media, users);
    }

    @Test
    void memberRejectionStopsBothReadEntrypoints() {
        doThrow(BusinessException.forbidden("非成员")).when(gatherings).requireMemberGathering(gatheringId, viewer);
        assertThrows(BusinessException.class, () -> service.browseGatheringMoments(gatheringId, new PageRequest<>(null, 20), viewer));
        assertThrows(BusinessException.class, () -> service.getGatheringMoment(gatheringId, UuidCreator.getTimeOrderedEpoch(), viewer));
        verifyNoInteractions(moments, images, media, users);
    }

    @Test
    void pageLoadsOnlyReturnedRowsAndDeduplicatesAuthors() {
        Moment first = moment(), second = moment(), extra = moment();
        when(moments.selectGatheringPage(gatheringId, null, null, 3)).thenReturn(List.of(first, second, extra));
        var profile = new PublicUserResponse(author, "作者", null);
        when(users.getPublicUsers(List.of(author))).thenReturn(Map.of(author, profile));
        var page = service.browseGatheringMoments(gatheringId, new PageRequest<>(null, 2), viewer);
        assertEquals(2, page.items().size());
        assertSame(profile, page.items().get(0).author());
        assertSame(profile, page.items().get(1).author());
        assertEquals(second.getId(), page.nextCursor().id());
        assertTrue(page.hasMore());
        verify(images).selectFeedImages(List.of(first.getId(), second.getId()));
        verify(users).getPublicUsers(List.of(author));
    }

    @Test
    void emptyAndLastPagesHaveNoNextCursorAndMissingAuthorStaysNull() {
        var empty = service.browseGatheringMoments(gatheringId, new PageRequest<>(null, 20), viewer);
        assertTrue(empty.items().isEmpty()); assertNull(empty.nextCursor());
        verifyNoInteractions(images, users);
        Moment moment = moment();
        var cursor = new GatheringMomentCursor(moment.getMemoryTime().toInstant(ZoneOffset.UTC), moment.getId());
        when(moments.selectGatheringPage(gatheringId, moment.getMemoryTime(), moment.getId(), 21)).thenReturn(List.of(moment));
        var last = service.browseGatheringMoments(gatheringId, new PageRequest<>(cursor, 20), viewer);
        assertNull(last.items().get(0).author()); assertNull(last.nextCursor()); assertFalse(last.hasMore());
    }

    @Test
    void controllerBindsStructuredCursorAndSerializesNullableAuthor() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GatheringController(gatherings, mock(GatheringFaceToFaceService.class), service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageQueryArgumentResolver(new JsonMapper())).build();
        Moment moment = moment();
        when(moments.selectById(moment.getId())).thenReturn(moment);
        mvc.perform(get("/gatherings/{g}/moments/{m}", gatheringId, moment.getId()).requestAttr("currentUserId", viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("data.id").value(moment.getId().toString()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"author\":null")));
        mvc.perform(get("/gatherings/{g}/moments", gatheringId).requestAttr("currentUserId", viewer)
                .param("cursor.memoryTime", "2026-01-01T00:00:00Z").param("cursor.id", moment.getId().toString()))
                .andExpect(status().isOk());
        verify(moments).selectGatheringPage(gatheringId, moment.getMemoryTime(), moment.getId(), 21);
        mvc.perform(get("/gatherings/{g}/moments", gatheringId).requestAttr("currentUserId", viewer)
                .param("cursor.id", moment.getId().toString())).andExpect(status().isBadRequest());
    }

    private Moment moment() {
        Moment moment = new Moment(); moment.setId(UuidCreator.getTimeOrderedEpoch());
        moment.setAuthorUserId(author); moment.setGatheringId(gatheringId); moment.setBody("正文");
        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0);
        moment.setMemoryTime(time); moment.setCreatedAt(time); moment.setUpdatedAt(time);
        return moment;
    }
}
