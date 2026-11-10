package com.you.youbackend.circle;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.circle.request.*;
import com.you.youbackend.circle.model.*;
import com.you.youbackend.circle.response.CircleResponse;
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

    private CircleResponse create(UUID id) {
        return service.createCircle(new CreateCircleRequest(id, "  家人  ", "介绍", null), user);
    }

    @Test void concurrentCreationCommitsOnceAndRejectsDuplicates() throws Exception {
        UUID id = newId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Object> task = () -> {
                start.await();
                try { return create(id); } catch (BusinessException exception) { return exception; }
            };
            Future<Object> one = pool.submit(task), two = pool.submit(task);
            start.countDown();
            var first = one.get(20, TimeUnit.SECONDS);
            var second = two.get(20, TimeUnit.SECONDS);
            var results = List.of(first, second);
            assertEquals(1, results.stream().filter(CircleResponse.class::isInstance).count());
            assertEquals(1, results.stream().filter(BusinessException.class::isInstance).count());
            var conflict = (BusinessException) results.stream().filter(BusinessException.class::isInstance).findFirst().orElseThrow();
            assertEquals("RESOURCE_CONFLICT", conflict.getCode());
            assertEquals(409, conflict.getStatus().value());
            assertEquals("家人", circles.selectById(id).getName());
            assertEquals(1, members.countActiveMembers(List.of(id)).get(0).memberCount());
            UUID badAvatar = UUID.randomUUID();
            assertEquals("RESOURCE_CONFLICT", assertThrows(BusinessException.class, () ->
                    service.createCircle(new CreateCircleRequest(id, "覆盖", null, badAvatar), user)).getCode());
            assertEquals("家人", circles.selectById(id).getName());
            verify(media, never()).validateOwnedImage(eq(badAvatar), any());
            assertEquals("RESOURCE_CONFLICT", assertThrows(BusinessException.class, () ->
                    service.createCircle(new CreateCircleRequest(id, "其他", null, null), UUID.randomUUID())).getCode());
            circles.deleteById(id);
            assertEquals("RESOURCE_CONFLICT", assertThrows(BusinessException.class, () -> create(id)).getCode());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void rollsBackBothRecordsWhenResponseLoadingFails() {
        UUID id = newId();
        when(media.createReadUrl(anyString())).thenThrow(new IllegalStateException("模拟签名失败"));
        assertThrows(IllegalStateException.class, () -> create(id));
        assertNull(circles.selectIncludingDeleted(id));
        assertFalse(members.isActiveMember(id, user));
    }

    @Test void preservesInvalidAvatarReferenceAndClearsNullableFields() {
        UUID id = newId(), avatar = UUID.randomUUID();
        create(id);
        var updated = service.updateCircle(id, new UpdateCircleRequest("新名称", null, avatar), user);
        assertEquals(avatar, updated.avatarFileId());
        assertEquals("https://example.test/default.png", updated.avatarUrl());
        assertNull(updated.description());
        updated = service.updateCircle(id, new UpdateCircleRequest("新名称", null, null), user);
        assertNull(circles.selectById(id).getAvatarFileId());
        assertNull(updated.avatarFileId());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.getCircle(id, UUID.randomUUID())).getStatus().value());
        UUID other = UUID.randomUUID();
        CircleMember member = new CircleMember();
        member.setId(UuidCreator.getTimeOrderedEpoch());
        member.setCircleId(id);
        member.setUserId(other);
        member.setCreatedAt(circles.selectById(id).getCreatedAt());
        member.setUpdatedAt(member.getCreatedAt());
        members.insert(member);
        assertFalse(service.getCircle(id, other).isCreator());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.updateCircle(id,
                new UpdateCircleRequest("禁止", null, null), other)).getStatus().value());
        members.deleteById(member.getId());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.getCircle(id, other)).getStatus().value());
    }

    @Test void rejectsUnusableImagesBeforeCreatingOrUpdating() {
        UUID id = newId(), avatar = UUID.randomUUID();
        doThrow(BusinessException.badRequest("FILE_NOT_USABLE", "图片不可用"))
                .when(media).validateOwnedImage(avatar, user);
        assertEquals("FILE_NOT_USABLE", assertThrows(BusinessException.class, () -> service.createCircle(
                new CreateCircleRequest(id, "家人", null, avatar), user)).getCode());
        assertNull(circles.selectIncludingDeleted(id));
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
