package com.you.youbackend.circle;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.circle.request.*;
import com.you.youbackend.circle.model.*;
import com.you.youbackend.circle.mapper.*;
import com.you.youbackend.circle.entity.CircleMember;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.user.service.UserService;
import com.you.youbackend.user.response.PublicUserResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 MySQL 验证事务、并发创建、UUID 映射与分页，外部资料及签名模拟。 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class CircleDatabaseTest {
    @Autowired CircleService service;
    @Autowired CircleMapper circles;
    @Autowired CircleMemberMapper members;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean UserService users;
    @MockitoBean MediaService media;
    private final UUID user = UUID.randomUUID();
    private final List<UUID> ids = new ArrayList<>();

    @BeforeEach void setup() {
        when(users.getPublicUsers(anyCollection())).thenAnswer(invocation -> {
            Map<UUID, PublicUserResponse> result = new HashMap<>();
            for (UUID id : (Collection<UUID>) invocation.getArgument(0)) {
                result.put(id, new PublicUserResponse(id, "测试用户", null));
            }
            return result;
        });
        when(media.getReadUrls(anyCollection())).thenReturn(Map.of());
        when(media.createReadUrl("defaults/circle-avatar.png")).thenReturn("https://example.test/default.png");
    }

    @AfterEach void cleanup() {
        // 仅清理本测试生成的 UUID，成功请求已经提交，不依靠测试事务回滚。
        for (UUID id : ids) {
            jdbc.update("DELETE FROM circle_member WHERE circle_id = UNHEX(REPLACE(?, '-', ''))", id.toString());
            jdbc.update("DELETE FROM circle WHERE id = UNHEX(REPLACE(?, '-', ''))", id.toString());
        }
    }

    private UUID newId() {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        ids.add(id);
        return id;
    }

    private void create(UUID id) {
        service.createCircle(new CreateCircleRequest(id, "  家人  ", "介绍", null), user);
    }

    @Test void invitationLifecycleRevalidatesAndRestoresMembership() {
        UUID id = newId();
        UUID guest = UUID.randomUUID();
        create(id);
        String code = service.getInvitation(id, user).inviteCode();
        var preview = service.previewJoin(new CircleJoinPreviewRequest(code), guest);
        assertEquals(id, preview.circle().id());
        assertFalse(preview.isMember());
        assertEquals(1, members.countActiveMembers(id));

        assertNull(service.updateInvitation(id, new UpdateCircleInvitationRequest(false), user).inviteCode());
        assertEquals("INVITATION_DISABLED", assertThrows(BusinessException.class,
                () -> service.join(new JoinCircleRequest(code), guest)).getCode());
        assertNull(service.resetInvitation(id, user).inviteCode());
        String replacement = service.updateInvitation(id, new UpdateCircleInvitationRequest(true), user).inviteCode();
        assertTrue(code.matches("[A-Z0-9]{16}"));
        assertNotEquals(code, replacement);
        assertTrue(replacement.matches("[A-Z0-9]{16}"));
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.join(new JoinCircleRequest(code), guest)).getStatus().value());

        var join = new JoinCircleRequest(replacement);
        service.join(join, guest);
        assertEquals(2, members.countActiveMembers(id));
        service.join(join, guest);
        assertEquals(2, members.countActiveMembers(id));
        assertTrue(service.previewJoin(new CircleJoinPreviewRequest(replacement), guest).isMember());
        assertEquals(replacement, service.getInvitation(id, guest).inviteCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.resetInvitation(id, guest)).getStatus().value());

        var membership = members.selectList(Wrappers.<CircleMember>lambdaQuery()
                .eq(CircleMember::getCircleId, id).eq(CircleMember::getUserId, guest)).get(0);
        members.update(Wrappers.<CircleMember>lambdaUpdate()
                .eq(CircleMember::getId, membership.getId()).set(CircleMember::getJoinRestricted, true)
                .set(CircleMember::getDeletedAt, membership.getCreatedAt()));
        assertEquals("JOIN_RESTRICTED", assertThrows(BusinessException.class, () -> service.join(join, guest)).getCode());
        assertEquals("JOIN_RESTRICTED", assertThrows(BusinessException.class,
                () -> service.previewJoin(new CircleJoinPreviewRequest(replacement), guest)).getCode());
        jdbc.update("UPDATE circle_member SET join_restricted = 0 WHERE id = UNHEX(REPLACE(?, '-', ''))",
                membership.getId().toString());
        service.join(join, guest);
        assertEquals(2, members.countActiveMembers(id));
        assertEquals(membership.getCreatedAt(), members.selectById(membership.getId()).getCreatedAt());
    }

    @Test void concurrentJoinsCreateOnlyOneMembership() throws Exception {
        UUID id = newId();
        UUID guest = UUID.randomUUID();
        create(id);
        var request = new JoinCircleRequest(service.getInvitation(id, user).inviteCode());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Void> join = () -> {
                start.await();
                service.join(request, guest);
                return null;
            };
            var first = pool.submit(join);
            var second = pool.submit(join);
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
            assertEquals(2, members.countActiveMembers(id));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void concurrentCreationCommitsOnceAndRejectsDuplicates() throws Exception {
        UUID id = newId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Object> task = () -> {
                start.await();
                try { create(id); return Boolean.TRUE; } catch (BusinessException exception) { return exception; }
            };
            Future<Object> one = pool.submit(task), two = pool.submit(task);
            start.countDown();
            var first = one.get(20, TimeUnit.SECONDS);
            var second = two.get(20, TimeUnit.SECONDS);
            var results = List.of(first, second);
            assertEquals(1, results.stream().filter(Boolean.TRUE::equals).count());
            assertEquals(1, results.stream().filter(BusinessException.class::isInstance).count());
            var conflict = (BusinessException) results.stream().filter(BusinessException.class::isInstance).findFirst().orElseThrow();
            assertEquals("RESOURCE_CONFLICT", conflict.getCode());
            assertEquals(409, conflict.getStatus().value());
            assertEquals("家人", circles.selectById(id).getName());
            assertEquals(1, members.countActiveMembers(id));
            assertEquals("RESOURCE_CONFLICT", assertThrows(BusinessException.class, () -> create(id)).getCode());
            circles.deleteById(id);
            assertEquals("RESOURCE_CONFLICT", assertThrows(BusinessException.class, () -> create(id)).getCode());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void writesCircleWithoutLoadingDisplayData() {
        UUID id = newId();
        create(id);
        service.updateCircle(id, new UpdateCircleRequest("新名称", null, null), user);
        assertNotNull(circles.selectById(id));
        assertTrue(members.isActiveMember(id, user));
        verify(users, never()).getPublicUsers(anyCollection());
        verify(media, never()).createReadUrl(anyString());
        verify(media, never()).getReadUrls(anyCollection());
    }

    @Test void preservesInvalidAvatarReferenceAndClearsNullableFields() {
        UUID id = newId(), avatar = UUID.randomUUID();
        create(id);
        service.updateCircle(id, new UpdateCircleRequest("新名称", null, avatar), user);
        var updated = service.getCircle(id, user);
        assertEquals(avatar, updated.avatarFileId());
        assertEquals("https://example.test/default.png", updated.avatarUrl());
        assertNull(updated.description());
        service.updateCircle(id, new UpdateCircleRequest("新名称", null, null), user);
        assertNull(circles.selectById(id).getAvatarFileId());
        assertNull(service.getCircle(id, user).avatarFileId());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.getCircle(id, UUID.randomUUID())).getStatus().value());
        UUID other = UUID.randomUUID();
        CircleMember member = new CircleMember();
        member.setId(UuidCreator.getTimeOrderedEpoch());
        member.setCircleId(id);
        member.setUserId(other);
        member.setCreatedAt(circles.selectById(id).getCreatedAt().plusSeconds(1));
        member.setUpdatedAt(member.getCreatedAt());
        members.insert(member);
        var detail = service.getCircle(id, other);
        assertFalse(detail.isCreator());
        assertEquals(user, detail.creator().id());
        assertEquals(2, detail.memberCount());
        assertEquals(List.of(user, other), detail.members().stream().map(entry -> entry.user().id()).toList());
        assertTrue(detail.members().get(0).isCreator());
        assertFalse(detail.members().get(1).isCreator());
        assertEquals(member.getCreatedAt().toInstant(java.time.ZoneOffset.UTC), detail.members().get(1).joinedAt());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.updateCircle(id,
                new UpdateCircleRequest("禁止", null, null), other)).getStatus().value());
        members.deleteById(member.getId());
        var afterRemoval = service.getCircle(id, user);
        assertEquals(1, afterRemoval.memberCount());
        assertEquals(List.of(user), afterRemoval.members().stream().map(entry -> entry.user().id()).toList());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.getCircle(id, other)).getStatus().value());
    }

    @Test void rejectsUnusableImagesBeforeCreatingOrUpdating() {
        UUID id = newId(), avatar = UUID.randomUUID();
        doThrow(BusinessException.badRequest("FILE_NOT_USABLE", "图片不可用"))
                .when(media).validateOwnedImage(avatar, user);
        assertEquals("FILE_NOT_USABLE", assertThrows(BusinessException.class, () -> service.createCircle(
                new CreateCircleRequest(id, "家人", null, avatar), user)).getCode());
        assertNull(circles.selectById(id));
        create(id);
        assertThrows(BusinessException.class, () -> service.updateCircle(id,
                new UpdateCircleRequest("不能保存", null, avatar), user));
        assertEquals("家人", circles.selectById(id).getName());
        assertEquals("介绍", circles.selectById(id).getDescription());
    }

    @Test void pagesByMembershipIdAndFiltersBeforeLimit() {
        UUID one = newId(), two = newId(), deleted = newId();
        create(one); create(two); create(deleted);
        circles.deleteById(deleted);
        jdbc.update("UPDATE circle_member SET created_at = '2026-11-10 06:00:00.000' WHERE user_id = UNHEX(REPLACE(?, '-', ''))", user.toString());
        var first = service.listMyCircles(user, new PageRequest<>(null, 1));
        assertTrue(first.hasMore());
        assertNotNull(first.nextCursor());
        assertNotEquals(first.items().get(0).id(), first.nextCursor().id());
        var second = service.listMyCircles(user, new PageRequest<>(first.nextCursor(), 1));
        assertFalse(second.hasMore());
        assertNull(second.nextCursor());
        assertNotEquals(first.items().get(0).id(), second.items().get(0).id());
        assertEquals(0, service.listMyCircles(UUID.randomUUID(), new PageRequest<>(null, 20)).items().size());
    }
}
