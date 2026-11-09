package com.you.youbackend.gathering;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.gathering.controller.GatheringController;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.entity.GatheringMember;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.request.GatheringJoinPreviewRequest;
import com.you.youbackend.gathering.request.JoinGatheringRequest;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.gathering.service.GatheringFaceToFaceService;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.user.filter.JwtAuthFilter;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 邀请码预览和加入的业务边界、GET 模型绑定及响应契约测试。 */
class GatheringInvitationTest {
    private final GatheringMapper gatherings = mock(GatheringMapper.class);
    private final GatheringMemberMapper members = mock(GatheringMemberMapper.class);
    private final UserService users = mock(UserService.class);
    private final MomentMapper moments = mock(MomentMapper.class);
    private final GatheringService service = new GatheringService(gatherings, members, users, moments);
    private final UUID userId = UuidCreator.getTimeOrderedEpoch();
    private final String inviteCode = "ABCDEFGHIJKLMNOP";

    @Test
    void previewReportsMembershipWithoutWritingOrLoadingContent() {
        Gathering gathering = availableGathering();
        when(members.isActiveMember(gathering.getId(), userId)).thenReturn(true);
        var response = service.previewJoin(new GatheringJoinPreviewRequest(inviteCode), userId);
        assertTrue(response.isMember());
        assertEquals(gathering.getId(), response.id());
        assertEquals(gathering.getCreatorUserId(), response.creator().id());
        verify(members, never()).joinOrRestore(any());
        verify(members, never()).insert(any(GatheringMember.class));
        verifyNoInteractions(moments);
    }

    @Test
    void joinReturnsRealCountsAndCreatesMembershipForAuthenticatedUser() {
        Gathering gathering = availableGathering();
        when(members.countActiveMembers(gathering.getId())).thenReturn(3L);
        when(moments.countByGathering(gathering.getId())).thenReturn(8L);
        var response = service.joinGathering(new JoinGatheringRequest(inviteCode), userId);
        assertEquals(3, response.memberCount());
        assertEquals(8, response.momentCount());
        assertEquals(gathering.getCreatorUserId(), response.creator().id());
        verify(members).joinOrRestore(argThat(member -> member.getId().version() == 7
                && member.getGatheringId().equals(gathering.getId())
                && member.getUserId().equals(userId)));
    }

    @Test
    void repeatJoinUsesExistingMembershipWithoutWriting() {
        Gathering gathering = availableGathering();
        when(members.isActiveMember(gathering.getId(), userId)).thenReturn(true);
        service.joinGathering(new JoinGatheringRequest(inviteCode), userId);
        verify(members, never()).joinOrRestore(any());
    }

    @Test
    void bothEndpointsRejectMissingOrDisabledInvitation() {
        for (boolean disabled : List.of(false, true)) {
            if (disabled) {
                Gathering gathering = availableGathering();
                gathering.setInviteEnabled(false);
            }
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () ->
                    service.previewJoin(new GatheringJoinPreviewRequest(inviteCode), userId)).getStatus());
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () ->
                    service.joinGathering(new JoinGatheringRequest(inviteCode), userId)).getStatus());
        }
        verifyNoInteractions(members, moments);
    }

    @Test
    void bindsGetQueryAndReturnsIsMemberAndCreatorAsDesigned() throws Exception {
        availableGathering();
        var mvc = MockMvcBuilders.standaloneSetup(new GatheringController(service, mock(GatheringFaceToFaceService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/gatherings/join-preview").param("inviteCode", inviteCode)
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.isMember").value(false))
                .andExpect(jsonPath("$.data.creator.nickname").value("创建者"))
                .andExpect(jsonPath("$.data.memberCount").doesNotExist());
        mvc.perform(post("/gatherings/join").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + inviteCode + "\"}")
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").exists());
    }

    @Test
    void invalidInviteCodesAreRejectedBeforeServiceQueries() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GatheringController(service, mock(GatheringFaceToFaceService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/gatherings/join-preview").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                .andExpect(status().isBadRequest());
        for (String code : List.of("", "  ", "A".repeat(17))) {
            mvc.perform(get("/gatherings/join-preview").param("inviteCode", code)
                            .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
            mvc.perform(post("/gatherings/join").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"inviteCode\":\"" + code + "\"}")
                            .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        mvc.perform(post("/gatherings/join").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(gatherings, members, users, moments);
    }

    /** 准备可加入同行和独立创建者资料，用于验证加入者与创建者身份的区别。 */
    private Gathering availableGathering() {
        Gathering gathering = new Gathering();
        gathering.setId(UuidCreator.getTimeOrderedEpoch());
        gathering.setCreatorUserId(UuidCreator.getTimeOrderedEpoch());
        gathering.setName("毕业旅行");
        gathering.setInviteCode(inviteCode);
        gathering.setInviteEnabled(true);
        gathering.setCreatedAt(LocalDateTime.of(2026, 11, 9, 0, 0));
        gathering.setUpdatedAt(gathering.getCreatedAt());
        when(gatherings.selectByInviteCode(inviteCode)).thenReturn(gathering);
        when(users.getPublicUser(gathering.getCreatorUserId()))
                .thenReturn(new PublicUserResponse(gathering.getCreatorUserId(), "创建者", null));
        return gathering;
    }
}