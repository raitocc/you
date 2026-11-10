package com.you.youbackend.gathering;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.gathering.controller.GatheringController;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.GatheringStatus;
import com.you.youbackend.gathering.request.AddGatheringMomentsRequest;
import com.you.youbackend.gathering.request.RemoveGatheringMomentsRequest;
import com.you.youbackend.gathering.service.GatheringFaceToFaceService;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 同行关联的批量校验、幂等、事务失败入口与请求边界测试。 */
class GatheringMomentAssociationTest {
    private final GatheringMapper gatherings = mock(GatheringMapper.class);
    private final GatheringMemberMapper members = mock(GatheringMemberMapper.class);
    private final MomentMapper moments = mock(MomentMapper.class);
    private final GatheringService service = new GatheringService(gatherings, members, mock(UserService.class), moments);
    private final UUID userId = UuidCreator.getTimeOrderedEpoch();
    private final UUID gatheringId = UuidCreator.getTimeOrderedEpoch();

    @Test
    void deduplicatesAndAddsOnlyUnassociatedMoments() {
        allowMember(GatheringStatus.ONGOING);
        Moment pending = moment(null), existing = moment(gatheringId);
        List<UUID> ids = List.of(pending.getId(), existing.getId());
        when(moments.selectOwnedForGathering(userId, ids)).thenReturn(List.of(pending, existing));
        when(moments.associateGathering(eq(userId), eq(gatheringId), eq(List.of(pending.getId())), any())).thenReturn(1);
        service.addMoments(gatheringId, new AddGatheringMomentsRequest(List.of(pending.getId(), existing.getId(), pending.getId())), userId);
        verify(moments).selectOwnedForGathering(userId, ids);
        verify(moments).associateGathering(eq(userId), eq(gatheringId), eq(List.of(pending.getId())), any());
    }

    @Test
    void repeatedAssociationSkipsWriting() {
        allowMember(GatheringStatus.ONGOING);
        Moment existing = moment(gatheringId);
        when(moments.selectOwnedForGathering(userId, List.of(existing.getId()))).thenReturn(List.of(existing));
        service.addMoments(gatheringId, new AddGatheringMomentsRequest(List.of(existing.getId())), userId);
        verify(moments, never()).associateGathering(any(), any(), any(), any());
    }

    @Test
    void otherGatheringRejectsWholeBatchBeforeWriting() {
        allowMember(GatheringStatus.ONGOING);
        Moment pending = moment(null), other = moment(UuidCreator.getTimeOrderedEpoch());
        var ids = List.of(pending.getId(), other.getId());
        when(moments.selectOwnedForGathering(userId, ids)).thenReturn(List.of(pending, other));
        var error = assertThrows(BusinessException.class,
                () -> service.addMoments(gatheringId, new AddGatheringMomentsRequest(ids), userId));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("MOMENT_ALREADY_ASSOCIATED", error.getCode());
        verify(moments, never()).associateGathering(any(), any(), any(), any());
    }

    @Test
    void missingDeletedOrForeignMomentRejectsBothOperationsBeforeWriting() {
        allowMember(GatheringStatus.ONGOING);
        var ids = List.of(UuidCreator.getTimeOrderedEpoch());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class,
                () -> service.addMoments(gatheringId, new AddGatheringMomentsRequest(ids), userId)).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class,
                () -> service.removeMoments(gatheringId, new RemoveGatheringMomentsRequest(ids), userId)).getStatus());
        verify(moments, never()).associateGathering(any(), any(), any(), any());
        verify(moments, never()).removeGathering(any(), any(), any(), any());
    }

    @Test
    void endedGatheringRejectsAddingButAllowsRemovingOnlyItsOwnLinks() {
        allowMember(GatheringStatus.ENDED);
        Moment current = moment(gatheringId), other = moment(UuidCreator.getTimeOrderedEpoch()), unlinked = moment(null);
        var ids = List.of(current.getId(), other.getId(), unlinked.getId());
        assertEquals("GATHERING_ENDED", assertThrows(BusinessException.class,
                () -> service.addMoments(gatheringId, new AddGatheringMomentsRequest(ids), userId)).getCode());
        when(moments.selectOwnedForGathering(userId, ids)).thenReturn(List.of(current, other, unlinked));
        service.removeMoments(gatheringId, new RemoveGatheringMomentsRequest(ids), userId);
        verify(moments).removeGathering(eq(userId), eq(gatheringId), eq(List.of(current.getId())), any());
    }

    @Test
    void repeatedRemovalSkipsWriting() {
        allowMember(GatheringStatus.ONGOING);
        Moment unlinked = moment(null);
        when(moments.selectOwnedForGathering(userId, List.of(unlinked.getId()))).thenReturn(List.of(unlinked));
        service.removeMoments(gatheringId, new RemoveGatheringMomentsRequest(List.of(unlinked.getId(), unlinked.getId())), userId);
        verify(moments, never()).removeGathering(any(), any(), any(), any());
    }

    @Test
    void missingGatheringAndNonMemberAreRejectedBeforeReadingMoments() {
        var ids = List.of(UuidCreator.getTimeOrderedEpoch());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.addMoments(gatheringId, new AddGatheringMomentsRequest(ids), userId)).getStatus());
        allowMember(GatheringStatus.ONGOING);
        when(members.isActiveMember(gatheringId, userId)).thenReturn(false);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.addMoments(gatheringId, new AddGatheringMomentsRequest(ids), userId)).getStatus());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.removeMoments(gatheringId, new RemoveGatheringMomentsRequest(ids), userId)).getStatus());
        verifyNoInteractions(moments);
    }

    @Test
    void changedAssociationDuringUpdateAbortsTransaction() {
        allowMember(GatheringStatus.ONGOING);
        Moment pending = moment(null);
        when(moments.selectOwnedForGathering(userId, List.of(pending.getId()))).thenReturn(List.of(pending));
        assertEquals("MOMENT_ASSOCIATION_CHANGED", assertThrows(BusinessException.class,
                () -> service.addMoments(gatheringId, new AddGatheringMomentsRequest(List.of(pending.getId())), userId)).getCode());
    }

    @Test
    void validatesBothRequestBodiesAndReturnsNullData() throws Exception {
        var target = mock(GatheringService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new GatheringController(target, mock(GatheringFaceToFaceService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UUID momentId = UuidCreator.getTimeOrderedEpoch();
        for (String suffix : List.of("", "/remove")) {
            String path = "/gatherings/" + gatheringId + "/moments" + suffix;
            for (String body : List.of("{}", "{\"momentIds\":[]}", "{\"momentIds\":[null]}",
                    "{\"momentIds\":[\"" + UUID.randomUUID() + "\"]}")) {
                mvc.perform(post(path).requestAttr("currentUserId", userId).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest());
            }
            mvc.perform(post(path).requestAttr("currentUserId", userId).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"momentIds\":[\"" + momentId + "\"]}"))
                    .andExpect(status().isOk()).andExpect(content().json("{\"code\":\"SUCCESS\",\"message\":\"操作成功\",\"data\":null}"));
        }
        verify(target).addMoments(gatheringId, new AddGatheringMomentsRequest(List.of(momentId)), userId);
        verify(target).removeMoments(gatheringId, new RemoveGatheringMomentsRequest(List.of(momentId)), userId);
        verifyNoMoreInteractions(target);
    }

    private void allowMember(GatheringStatus status) {
        Gathering gathering = new Gathering();
        gathering.setId(gatheringId);
        gathering.setStatus(status);
        gathering.setInviteEnabled(false);
        when(gatherings.selectById(gatheringId)).thenReturn(gathering);
        when(members.isActiveMember(gatheringId, userId)).thenReturn(true);
    }

    private Moment moment(UUID linkedGathering) {
        Moment moment = new Moment();
        moment.setId(UuidCreator.getTimeOrderedEpoch());
        moment.setAuthorUserId(userId);
        moment.setGatheringId(linkedGathering);
        return moment;
    }
}
