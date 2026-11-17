package com.you.youbackend.gathering;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.gathering.controller.GatheringController;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.entity.GatheringMember;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.GatheringStatus;
import com.you.youbackend.gathering.request.CreateGatheringRequest;
import com.you.youbackend.gathering.service.GatheringFaceToFaceService;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.filter.JwtAuthFilter;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 创建同行的业务和请求边界测试；真实事务由 GatheringDatabaseTest 验证。 */
class GatheringCreationTest {
    private final GatheringMapper gatherings = mock(GatheringMapper.class);
    private final GatheringMemberMapper members = mock(GatheringMemberMapper.class);
    private final UserService users = mock(UserService.class);
    private final GatheringService service = new GatheringService(gatherings, members, users, mock(MomentMapper.class));
    private final UUID userId = UuidCreator.getTimeOrderedEpoch();

    @Test
    void createsGatheringAndCreatorMembershipWithSharedTimestamp() {
        // 当前用户公开资料用于响应，创建者和首位成员身份来自认证上下文。
        var creator = new PublicUserResponse(userId, "昵称", null);
        when(users.getPublicUser(userId)).thenReturn(creator);
        var result = service.createGathering(new CreateGatheringRequest("  周末出游  ", null), userId);
        var gathering = ArgumentCaptor.forClass(Gathering.class);
        var member = ArgumentCaptor.forClass(GatheringMember.class);
        verify(gatherings).insert(gathering.capture());
        verify(members).insert(member.capture());

        assertEquals(GatheringStatus.ONGOING, result.status());
        assertEquals("周末出游", result.name());
        assertSame(creator, result.creator());
        assertNull(result.description());
        assertEquals(1, result.memberCount());
        assertEquals(0, result.momentCount());
        assertEquals(7, result.id().version());
        assertTrue(result.inviteCode().matches("[A-Z0-9]{16}"));
        assertEquals(userId, gathering.getValue().getCreatorUserId());
        assertEquals(GatheringStatus.ONGOING, gathering.getValue().getStatus());
        assertTrue(gathering.getValue().getInviteEnabled());
        assertEquals(result.id(), member.getValue().getGatheringId());
        assertEquals(userId, member.getValue().getUserId());
        assertEquals(7, member.getValue().getId().version());
        assertNull(gathering.getValue().getDeletedAt());
        assertNull(member.getValue().getDeletedAt());
        assertEquals(gathering.getValue().getCreatedAt(), member.getValue().getCreatedAt());
        assertEquals(member.getValue().getCreatedAt(), member.getValue().getUpdatedAt());
        assertEquals(result.createdAt(), result.updatedAt());
    }

    @Test
    void rejectsInvalidAccountBeforeWriting() {
        when(users.getPublicUser(userId)).thenThrow(BusinessException.unauthorized("账号不存在或已注销，请重新登录"));
        assertThrows(BusinessException.class,
                () -> service.createGathering(new CreateGatheringRequest("出游", null), userId));
        verifyNoInteractions(gatherings, members);
    }

    @Test
    void convertsGeneratedIdentifierConflictToBusinessException() {
        when(users.getPublicUser(userId)).thenReturn(new PublicUserResponse(userId, "昵称", null));
        var cause = new DuplicateKeyException("模拟唯一索引冲突");
        doThrow(cause).when(gatherings).insert(any(Gathering.class));
        var exception = assertThrows(BusinessException.class,
                () -> service.createGathering(new CreateGatheringRequest("出游", null), userId));
        assertEquals("GATHERING_CREATE_CONFLICT", exception.getCode());
        assertSame(cause, exception.getCause());
        verifyNoInteractions(members);
    }

    @Test
    void validatesTrimmedNameAndUnicodeCodePointLimits() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new CreateGatheringRequest(
                    "  " + "😀".repeat(128) + "  ", "😀".repeat(5000))).isEmpty());
            for (var request : List.of(
                    new CreateGatheringRequest(null, null),
                    new CreateGatheringRequest(" \t\n", null),
                    new CreateGatheringRequest("😀".repeat(129), null),
                    new CreateGatheringRequest("出游", "😀".repeat(5001)))) {
                assertFalse(validator.validate(request).isEmpty());
            }
        }
    }

    @Test
    void returns200WithTypedResponseAndRejectsInvalidBodies() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GatheringController(service, mock(GatheringFaceToFaceService.class), mock(MomentService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.getPublicUser(userId)).thenReturn(new PublicUserResponse(userId, "昵称", null));
        mvc.perform(post("/gatherings").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  出游  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("出游"))
                .andExpect(jsonPath("$.data.creator.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.creator.username").doesNotExist())
                .andExpect(jsonPath("$.data.memberCount").value(1))
                .andExpect(jsonPath("$.data.momentCount").value(0));

        // 无效请求在 MVC 边界拒绝，业务层和数据库保持零调用。
        clearInvocations(users, gatherings, members);
        for (String body : List.of("{}", "{\"name\":null}", "{\"name\":\" \"}", "{")) {
            mvc.perform(post("/gatherings").requestAttr(JwtAuthFilter.CURRENT_USER_ID, userId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
        verifyNoInteractions(users, gatherings, members);
    }
}
