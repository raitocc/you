package com.you.youbackend.circle;
import com.you.youbackend.circle.service.*;
import com.you.youbackend.circle.entity.Circle;
import com.you.youbackend.circle.entity.CircleShare;
import com.you.youbackend.circle.mapper.*;
import com.you.youbackend.circle.model.CircleShareObjectType;
import com.you.youbackend.circle.request.CreateCircleSharesRequest;
import com.you.youbackend.album.mapper.*;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.user.service.UserService;
import com.you.youbackend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 验证数据库并发冲突向外抛出，不自动重试或覆盖已有分享。 */
class CircleShareConflictTest {
    final CircleShareMapper shares = mock(CircleShareMapper.class);
    final CircleMemberMapper members = mock(CircleMemberMapper.class);
    final MomentMapper moments = mock(MomentMapper.class);
    final UserService users = mock(UserService.class);
    final CircleMapper circles = mock(CircleMapper.class);
    final CircleService service = new CircleService(circles, members, users, mock(MediaService.class), shares, moments,
            mock(AlbumMapper.class), mock(AlbumMomentMapper.class), mock(MomentService.class), "defaults/circle-avatar.png");
    final UUID user = UUID.randomUUID(), circle = UUID.randomUUID(), object = UUID.randomUUID();
    final CreateCircleSharesRequest request = new CreateCircleSharesRequest(CircleShareObjectType.moment, object, List.of(circle));
    void setup() {
        Moment moment = new Moment(); moment.setAuthorUserId(user);
        when(moments.selectById(object)).thenReturn(moment);
        when(members.selectActiveCircleIds(user, List.of(circle))).thenReturn(List.of(circle));
        when(circles.selectByIds(List.of(circle))).thenReturn(List.of(new Circle()));
    }
    @Test void duplicateKeyReturns409WithCauseWithoutRetry() {
        setup();
        when(shares.selectHistory(request.objectType(), object, List.of(circle))).thenReturn(List.of());
        DuplicateKeyException cause = new DuplicateKeyException("模拟并发唯一冲突");
        when(shares.insertShares(anyList())).thenThrow(cause);
        var error = assertThrows(BusinessException.class, () -> service.shareToCircles(request, user));
        assertEquals(409, error.getStatus().value()); assertSame(cause, error.getCause());
        verify(shares, times(1)).insertShares(anyList());
        verify(shares, times(1)).selectHistory(request.objectType(), object, List.of(circle));
        verifyNoInteractions(users);
    }
    @Test void concurrentRestoreCountMismatchReturns409WithoutRetry() {
        setup();
        CircleShare share = new CircleShare(); share.setId(UUID.randomUUID()); share.setCircleId(circle);
        share.setDeletedAt(LocalDateTime.now());
        when(shares.selectHistory(request.objectType(), object, List.of(circle))).thenReturn(List.of(share));
        when(shares.restoreShares(anyList(), eq(user), any())).thenReturn(0);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.shareToCircles(request, user)).getStatus().value());
        verify(shares, times(1)).restoreShares(anyList(), eq(user), any());
        verify(shares, never()).insertShares(anyList()); verifyNoInteractions(users);
    }
}
