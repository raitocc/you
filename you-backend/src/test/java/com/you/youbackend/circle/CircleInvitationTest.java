package com.you.youbackend.circle;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.circle.entity.Circle;
import com.you.youbackend.circle.entity.CircleMember;
import com.you.youbackend.circle.mapper.CircleMapper;
import com.you.youbackend.circle.mapper.CircleMemberMapper;
import com.you.youbackend.circle.mapper.CircleShareMapper;
import com.you.youbackend.circle.request.CircleJoinPreviewRequest;
import com.you.youbackend.circle.request.CreateCircleRequest;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证创建分配字符串邀请码、重置使旧码失效及唯一索引冲突的业务响应。 */
class CircleInvitationTest {
    private final CircleMapper circles = mock(CircleMapper.class);
    private final CircleMemberMapper members = mock(CircleMemberMapper.class);
    private final UserService users = mock(UserService.class);
    private final CircleService service = new CircleService(circles, members, users, mock(MediaService.class),
            mock(CircleShareMapper.class), mock(MomentMapper.class), mock(AlbumMapper.class),
            mock(AlbumMomentMapper.class), mock(MomentService.class), "defaults/circle-avatar.png");
    private final UUID userId = UUID.randomUUID();
    private final UUID circleId = UuidCreator.getTimeOrderedEpoch();

    @Test
    void creationStoresRandomStringAndInitialMembership() {
        // 创建直接保存随机码，无需额外发号记录。
        service.createCircle(new CreateCircleRequest(circleId, "家人", null, null), userId);
        var saved = ArgumentCaptor.forClass(Circle.class);
        verify(circles).insert(saved.capture());
        assertTrue(saved.getValue().getInviteCode().matches("[A-Z0-9]{16}"));
        assertTrue(saved.getValue().getInviteEnabled());
        verify(members).insert(argThat((CircleMember member) -> member.getCircleId().equals(circleId)
                && member.getUserId().equals(userId)));
    }

    @Test
    void resetReplacesCodeAndPreviewOnlyAcceptsCurrentCode() {
        Circle circle = arrangeCreator();
        String previous = circle.getInviteCode();
        when(circles.updateInviteCode(circle)).thenReturn(1);
        // 按当前保存的邀请码模拟查询，旧码不再指向圈子。
        when(circles.findByInviteCode(anyString())).thenAnswer(call ->
                circle.getInviteCode().equals(call.getArgument(0)) ? circle : null);
        when(users.getPublicUsers(List.of(userId))).thenReturn(Map.of(userId,
                new PublicUserResponse(userId, "创建者", null)));

        var result = service.resetInvitation(circleId, userId);
        assertTrue(result.inviteCode().matches("[A-Z0-9]{16}"));
        assertNotEquals(previous, result.inviteCode());
        assertTrue(result.inviteEnabled());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.previewJoin(
                new CircleJoinPreviewRequest(previous), userId)).getStatus().value());
        assertEquals(circleId, service.previewJoin(
                new CircleJoinPreviewRequest(result.inviteCode()), userId).circle().id());
    }

    @Test
    void resetReportsUniqueConstraintConflict() {
        Circle circle = arrangeCreator();
        when(circles.updateInviteCode(circle)).thenThrow(new DuplicateKeyException("duplicate"));
        assertEquals("RESOURCE_CONFLICT", assertThrows(BusinessException.class,
                () -> service.resetInvitation(circleId, userId)).getCode());
    }

    /** 准备创建者访问资格及原字符串邀请码，供重置流程使用。 */
    private Circle arrangeCreator() {
        Circle circle = new Circle();
        circle.setId(circleId);
        circle.setCreatorUserId(userId);
        circle.setInviteEnabled(true);
        circle.setInviteCode("ABCD1234EFGH5678");
        when(circles.selectById(circleId)).thenReturn(circle);
        when(members.isActiveMember(circleId, userId)).thenReturn(true);
        return circle;
    }
}
