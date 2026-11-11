package com.you.youbackend.circle;

import com.you.youbackend.circle.entity.Circle;
import com.you.youbackend.circle.entity.CircleMember;
import com.you.youbackend.circle.mapper.CircleMapper;
import com.you.youbackend.circle.mapper.CircleIdentityMapper;
import com.you.youbackend.circle.mapper.CircleInviteNumberMapper;
import com.you.youbackend.circle.mapper.CircleMemberHistoryMapper;
import com.you.youbackend.circle.mapper.CircleMemberMapper;
import com.you.youbackend.circle.model.CircleListRow;
import com.you.youbackend.circle.mapper.CircleShareMapper;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.user.service.UserService;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 列表只加载摘要和当前页头像，详情依赖不应参与列表查询。 */
class CircleListTest {
    private final CircleMapper circles = mock(CircleMapper.class);
    private final CircleMemberMapper members = mock(CircleMemberMapper.class);
    private final UserService users = mock(UserService.class);
    private final MediaService media = mock(MediaService.class);
    private final CircleService service = new CircleService(circles, members, users, media, mock(CircleShareMapper.class),
            mock(MomentMapper.class), mock(AlbumMapper.class),
            mock(AlbumMomentMapper.class), mock(MomentService.class), mock(CircleMemberHistoryMapper.class), mock(CircleIdentityMapper.class), mock(CircleInviteNumberMapper.class), "defaults/circle-avatar.png");
    private final UUID user = UUID.randomUUID();

    @Test void loadsOnlyVisiblePageAvatarsWithoutProfilesOrMemberCounts() {
        UUID custom = UUID.randomUUID(), invalid = UUID.randomUUID(), extra = UUID.randomUUID();
        var first = row(custom);
        var second = row(invalid);
        var third = row(null);
        var candidates = List.of(first, second, third, row(extra));
        when(members.selectPageCandidates(user, null, null, 4))
                .thenReturn(candidates.stream().map(this::membership).toList());
        when(circles.selectByIds(candidates.stream().map(CircleListRow::getId).toList()))
                .thenReturn(candidates.stream().map(this::circle).toList());
        when(media.getReadUrls(List.of(custom, invalid))).thenReturn(Map.of(custom, "custom-url"));
        when(media.createReadUrl("defaults/circle-avatar.png")).thenReturn("default-url");

        var result = service.listMyCircles(user, new PageRequest<>(null, 3));

        assertEquals(3, result.items().size());
        assertEquals("custom-url", result.items().get(0).avatarUrl());
        assertEquals("default-url", result.items().get(1).avatarUrl());
        assertEquals("default-url", result.items().get(2).avatarUrl());
        assertEquals(first.getId(), result.items().get(0).id());
        assertEquals(first.getName(), result.items().get(0).name());
        assertTrue(result.hasMore());
        assertEquals(third.getMembershipId(), result.nextCursor().id());
        verify(media).getReadUrls(List.of(custom, invalid));
        verify(members, never()).countActiveMembers(any(UUID.class));
        verifyNoInteractions(users);
    }

    @Test void emptyPageSkipsAllDisplayDependencies() {
        when(members.selectPageCandidates(user, null, null, 21)).thenReturn(List.of());
        var result = service.listMyCircles(user, new PageRequest<>(null, 20));
        assertTrue(result.items().isEmpty());
        assertFalse(result.hasMore());
        assertNull(result.nextCursor());
        verifyNoInteractions(users, circles, media);
        verify(members, never()).countActiveMembers(any(UUID.class));
    }

    private CircleMember membership(CircleListRow row) {
        var member = new CircleMember();
        member.setId(row.getMembershipId());
        member.setCircleId(row.getId());
        member.setCreatedAt(row.getJoinedAt());
        return member;
    }

    private Circle circle(CircleListRow row) {
        var circle = new Circle();
        circle.setId(row.getId());
        circle.setName(row.getName());
        circle.setAvatarFileId(row.getAvatarFileId());
        return circle;
    }

    private CircleListRow row(UUID avatar) {
        var row = new CircleListRow();
        row.setId(UUID.randomUUID());
        row.setName("圈子名称");
        row.setAvatarFileId(avatar);
        row.setJoinedAt(LocalDateTime.of(2026, 11, 11, 0, 0));
        row.setMembershipId(UUID.randomUUID());
        return row;
    }
}
