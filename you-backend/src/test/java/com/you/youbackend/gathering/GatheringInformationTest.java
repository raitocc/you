package com.you.youbackend.gathering;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.common.request.PageQueryArgumentResolver;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.gathering.controller.GatheringController;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.entity.GatheringMember;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.*;
import com.you.youbackend.gathering.request.UpdateGatheringRequest;
import com.you.youbackend.gathering.service.GatheringFaceToFaceService;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 同行基本信息的权限、分页组装、全量更新及 MVC 契约测试。 */
class GatheringInformationTest {
    private final GatheringMapper gatherings = mock(GatheringMapper.class);
    private final GatheringMemberMapper members = mock(GatheringMemberMapper.class);
    private final MomentMapper moments = mock(MomentMapper.class);
    private final UserService users = mock(UserService.class);
    private final GatheringService service = new GatheringService(gatherings, members, users, moments);
    private final UUID userId = UuidCreator.getTimeOrderedEpoch();
    private final GatheringService controllerService = mock(GatheringService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new GatheringController(
            controllerService, mock(GatheringFaceToFaceService.class), mock(MomentService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PageQueryArgumentResolver(new JsonMapper())).build();

    @Test
    void batchesCountsAndTruncatesUnicodeWithoutChangingEntity() {
        Gathering first = gathering(), extra = gathering();
        String original = "😀".repeat(101);
        first.setDescription(original);
        when(gatherings.selectFirstPage(userId, 2)).thenReturn(List.of(first, extra));
        when(members.countByGatherings(List.of(first.getId()))).thenReturn(List.of(count(first.getId(), 3)));
        when(moments.countByGatherings(List.of(first.getId()))).thenReturn(List.of());
        var creator = new PublicUserResponse(userId, "创建者", "https://example.test/avatar");
        when(users.getPublicUsers(List.of(userId))).thenReturn(Map.of(userId, creator));
        var result = service.browseGatherings(new PageRequest<>(null, 1), userId);
        assertTrue(result.hasMore());
        assertEquals(first.getId(), result.nextCursor().id());
        assertEquals("😀".repeat(100), result.items().get(0).descriptionPreview());
        assertEquals(3, result.items().get(0).memberCount());
        assertEquals(0, result.items().get(0).momentCount());
        assertEquals(original, first.getDescription());
        assertSame(creator, result.items().get(0).creator());
        verify(users).getPublicUsers(List.of(userId));
    }

    @Test
    void emptyPageSkipsCountsAndLastPageHasNoCursor() {
        when(gatherings.selectFirstPage(userId, 21)).thenReturn(List.of());
        var empty = service.browseGatherings(new PageRequest<>(null, 20), userId);
        assertTrue(empty.items().isEmpty());
        assertNull(empty.nextCursor());
        verifyNoInteractions(members, moments, users);
        Gathering last = gathering();
        when(users.getPublicUsers(List.of(userId))).thenReturn(Map.of(userId, new PublicUserResponse(userId, "创建者", null)));
        var cursor = new GatheringListCursor(Instant.parse("2026-01-01T00:00:00Z"), last.getId());
        when(gatherings.selectNextPage(userId, LocalDateTime.ofInstant(cursor.createdAt(), ZoneOffset.UTC), cursor.id(), 21))
                .thenReturn(List.of(last));
        var result = service.browseGatherings(new PageRequest<>(cursor, 20), userId);
        assertFalse(result.hasMore());
        assertNull(result.nextCursor());
        assertNull(result.items().get(0).descriptionPreview());
    }

    @Test
    void detailRejectsMissingGatheringAndNonMemberBeforeLoadingProfiles() {
        UUID missing = UuidCreator.getTimeOrderedEpoch();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.getGathering(missing, userId)).getStatus());
        Gathering gathering = gathering();
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.getGathering(gathering.getId(), userId)).getStatus());
        verifyNoInteractions(users, moments);
    }

    @Test
    void endedGatheringWithInvitationsDisabledRemainsReadable() {
        Gathering gathering = gathering();
        gathering.setStatus(GatheringStatus.ENDED);
        gathering.setInviteEnabled(false);
        arrangeReadable(gathering);
        when(members.countActiveMembers(gathering.getId())).thenReturn(2L);
        when(moments.countByGathering(gathering.getId())).thenReturn(4L);
        var response = service.getGathering(gathering.getId(), userId);
        assertEquals(2, response.memberCount());
        assertEquals(4, response.momentCount());
        assertEquals(userId, response.creator().id());
    }

    @Test
    void updateClearsDescriptionAndPreservesOtherSettings() {
        Gathering gathering = gathering();
        gathering.setDescription("原介绍");
        gathering.setFaceCode("2005");
        gathering.setStatus(GatheringStatus.ENDED);
        gathering.setInviteEnabled(false);
        arrangeReadable(gathering);
        LocalDateTime createdAt = gathering.getCreatedAt();
        var result = service.updateGathering(gathering.getId(), new UpdateGatheringRequest("  新名字  ", null), userId);
        verify(gatherings).updateContent(gathering);
        assertEquals("新名字", result.name());
        assertNull(result.description());
        assertEquals(createdAt, gathering.getCreatedAt());
        assertEquals("2005", gathering.getFaceCode());
        assertEquals(GatheringStatus.ENDED, gathering.getStatus());
        assertFalse(gathering.getInviteEnabled());
        assertEquals("ABCDEFGHIJKLMNOP", gathering.getInviteCode());
    }

    @Test
    void ordinaryMemberCannotUpdate() {
        Gathering gathering = gathering();
        gathering.setCreatorUserId(UuidCreator.getTimeOrderedEpoch());
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.updateGathering(gathering.getId(), new UpdateGatheringRequest("名字", null), userId)).getStatus());
        verify(gatherings, never()).updateContent(any());
        verifyNoInteractions(users);
    }

    @Test
    void memberPagePreservesRelationOrderAndBuildsCursor() {
        Gathering gathering = gathering();
        arrangeReadable(gathering);
        GatheringMember first = membership(userId), second = membership(UuidCreator.getTimeOrderedEpoch());
        when(members.selectFirstPage(gathering.getId(), 2)).thenReturn(List.of(first, second));
        var result = service.browseMembers(gathering.getId(), new PageRequest<>(null, 1), userId);
        assertEquals(userId, result.items().get(0).id());
        assertTrue(result.hasMore());
        assertEquals(first.getCreatedAt().toInstant(ZoneOffset.UTC), result.nextCursor().createdAt());
        assertEquals(userId, result.nextCursor().userId());
    }

    @Test
    void emptyMemberPageReturnsEmptyAndLaterPageUsesRelationCursor() {
        Gathering gathering = gathering();
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        when(members.isActiveMember(gathering.getId(), userId)).thenReturn(true);
        var cursor = new GatheringMemberCursor(Instant.parse("2026-01-01T00:00:00Z"), userId);
        var result = service.browseMembers(gathering.getId(), new PageRequest<>(cursor, 20), userId);
        assertTrue(result.items().isEmpty());
        assertFalse(result.hasMore());
        assertNull(result.nextCursor());
        verify(members).selectNextPage(gathering.getId(), LocalDateTime.ofInstant(cursor.createdAt(), ZoneOffset.UTC), userId, 21);
        verify(users).getPublicUsers(List.of());
    }

    @Test
    void missingUserProfilesRemainNullWithoutFailingOrHidingRecords() {
        Gathering gathering = gathering();
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        when(members.isActiveMember(gathering.getId(), userId)).thenReturn(true);
        when(users.getPublicUsers(List.of(userId))).thenReturn(Map.of());
        when(members.selectFirstPage(gathering.getId(), 21)).thenReturn(List.of(membership(userId)));
        var membersPage = service.browseMembers(gathering.getId(), new PageRequest<>(null, 20), userId);
        assertEquals(1, membersPage.items().size());
        assertNull(membersPage.items().get(0));
        assertNull(service.getGathering(gathering.getId(), userId).creator());
        when(gatherings.selectFirstPage(userId, 21)).thenReturn(List.of(gathering));
        var gatheringsPage = service.browseGatherings(new PageRequest<>(null, 20), userId);
        assertEquals(1, gatheringsPage.items().size());
        assertNull(gatheringsPage.items().get(0).creator());
        assertNull(service.updateGathering(gathering.getId(), new UpdateGatheringRequest("名称", null), userId).creator());
    }

    @Test
    void getBindsBothGenericCursorTypes() throws Exception {
        String time = "2026-01-01T00:00:00Z";
        mvc.perform(get("/gatherings").requestAttr("currentUserId", userId)
                .param("cursor.createdAt", time).param("cursor.id", userId.toString()).param("limit", "5"))
                .andExpect(status().isOk());
        verify(controllerService).browseGatherings(new PageRequest<>(new GatheringListCursor(Instant.parse(time), userId), 5), userId);
        mvc.perform(get("/gatherings/{id}/members", userId).requestAttr("currentUserId", userId)
                .param("cursor.createdAt", time).param("cursor.userId", userId.toString()))
                .andExpect(status().isOk());
        verify(controllerService).browseMembers(userId, new PageRequest<>(new GatheringMemberCursor(Instant.parse(time), userId), 20), userId);
    }

    @Test
    void rejectsPartialCursorAndInvalidLimitBeforeService() throws Exception {
        mvc.perform(get("/gatherings").requestAttr("currentUserId", userId).param("cursor.id", userId.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/gatherings/{id}/members", userId).requestAttr("currentUserId", userId).param("limit", "101"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(controllerService);
    }

    @Test
    void putRequiresDescriptionButAcceptsExplicitNull() throws Exception {
        mvc.perform(put("/gatherings/{id}", userId).requestAttr("currentUserId", userId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"新名字\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(controllerService);
        mvc.perform(put("/gatherings/{id}", userId).requestAttr("currentUserId", userId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  新名字  \",\"description\":null}"))
                .andExpect(status().isOk());
        verify(controllerService).updateGathering(userId, new UpdateGatheringRequest("新名字", null), userId);
    }

    private Gathering gathering() {
        Gathering gathering = new Gathering();
        gathering.setId(UuidCreator.getTimeOrderedEpoch());
        gathering.setCreatorUserId(userId);
        gathering.setName("同行");
        gathering.setStatus(GatheringStatus.ONGOING);
        gathering.setInviteEnabled(true);
        gathering.setInviteCode("ABCDEFGHIJKLMNOP");
        gathering.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        gathering.setUpdatedAt(gathering.getCreatedAt());
        return gathering;
    }

    private GatheringMember membership(UUID id) {
        GatheringMember member = new GatheringMember();
        member.setUserId(id);
        member.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return member;
    }

    private GatheringCount count(UUID id, long value) {
        GatheringCount result = new GatheringCount();
        result.setGatheringId(id);
        result.setCount(value);
        return result;
    }

    private void arrangeReadable(Gathering gathering) {
        when(gatherings.selectById(gathering.getId())).thenReturn(gathering);
        when(members.isActiveMember(gathering.getId(), userId)).thenReturn(true);
        when(users.getPublicUsers(List.of(userId))).thenReturn(Map.of(userId, new PublicUserResponse(userId, "昵称", null)));
    }
}
