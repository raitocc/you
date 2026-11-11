package com.you.youbackend.circle;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.circle.entity.*;
import com.you.youbackend.circle.mapper.*;
import com.you.youbackend.circle.model.*;
import com.you.youbackend.circle.request.CreateCircleSharesRequest;
import com.you.youbackend.album.entity.*;
import com.you.youbackend.album.mapper.*;
import com.you.youbackend.album.model.AlbumOrganizeMode;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.user.service.UserService;
import com.you.youbackend.user.response.PublicUserResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 MySQL 验证分享 SQL、恢复、权限、游标、失效过滤和整批回滚；媒体签名及用户资料模拟。 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class CircleShareDatabaseTest {
    @Autowired CircleService service;
    @Autowired CircleShareMapper shares;
    @Autowired CircleMapper circles;
    @Autowired CircleMemberMapper members;
    @Autowired MomentMapper moments;
    @Autowired AlbumMapper albums;
    @Autowired CircleInviteNumberMapper inviteNumbers;
    @Autowired AlbumMomentMapper associations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean UserService users;
    @MockitoBean MediaService media;
    final UUID owner = UUID.randomUUID(), viewer = UUID.randomUUID(), stranger = UUID.randomUUID();
    final LocalDateTime time = LocalDateTime.of(2026, 11, 11, 0, 0);
    final List<UUID> circleIds = new ArrayList<>(), momentIds = new ArrayList<>(), albumIds = new ArrayList<>();
    UUID circle;

    @BeforeEach void setup() {
        when(users.getPublicUsers(anyCollection())).thenAnswer(invocation -> {
            Map<UUID, PublicUserResponse> result = new HashMap<>();
            for (UUID id : (Collection<UUID>) invocation.getArgument(0)) result.put(id, new PublicUserResponse(id, "测试用户", null));
            return result;
        });
        when(media.findImageKeys(anyCollection())).thenReturn(Map.of());
        circle = newCircle();
    }

    @AfterEach void cleanup() {
        // 仅清理测试生成的对象，已提交写入不依赖测试事务回滚。
        for (UUID id : circleIds) {
            jdbc.update("DELETE FROM circle_share WHERE circle_id=?", bytes(id));
            jdbc.update("DELETE FROM circle_member WHERE circle_id=?", bytes(id));
            jdbc.update("DELETE FROM circle WHERE id=?", bytes(id));
        }
        for (UUID id : albumIds) {
            jdbc.update("DELETE FROM album_moment WHERE album_id=?", bytes(id));
            jdbc.update("DELETE FROM album WHERE id=?", bytes(id));
        }
        for (UUID id : momentIds) jdbc.update("DELETE FROM moment WHERE id=?", bytes(id));
    }

    @Test void duplicateKeepsTimeAndRestoreKeepsIdentity() {
        UUID moment = newMoment(time, time);
        var request = request(CircleShareObjectType.moment, moment, List.of(circle, newCircle()));
        service.shareToCircles(request, owner);
        var share = shares.selectHistory(request.objectType(), moment, List.of(circle)).get(0);
        UUID otherCircle = request.circleIds().get(1);
        var otherShare = shares.selectHistory(request.objectType(), moment, List.of(otherCircle)).get(0);
        service.shareToCircles(request, owner);
        assertEquals(share.getSharedAt(), shares.selectHistoryById(circle, share.getId()).getSharedAt());
        service.withdrawShare(circle, share.getId(), owner);
        service.withdrawShare(circle, share.getId(), owner);
        assertTrue(service.browseShares(circle, viewer, new PageRequest<>(null, 20)).items().isEmpty());
        service.shareToCircles(request, owner);
        var restored = shares.selectHistoryById(circle, share.getId());
        assertNotNull(restored);
        assertFalse(restored.getSharedAt().isBefore(share.getSharedAt()));
        assertNull(restored.getDeletedAt());
        assertEquals(otherShare.getSharedAt(), shares.selectHistoryById(otherCircle, otherShare.getId()).getSharedAt());
    }

    @Test void invalidTargetLeavesNoPartialWrites() {
        UUID moment = newMoment(time, time);
        var bad = request(CircleShareObjectType.moment, moment, List.of(circle, UUID.randomUUID()));
        assertEquals(404, assertThrows(BusinessException.class, () -> service.shareToCircles(bad, owner)).getStatus().value());
        assertTrue(shares.selectHistory(CircleShareObjectType.moment, moment, List.of(circle)).isEmpty());
    }

    @Test void batchInsertTranslatesDuplicateAndRollsBackEarlierRows() {
        UUID moment = newMoment(time, time);
        service.shareToCircles(request(CircleShareObjectType.moment, moment, List.of(circle)), owner);
        CircleShare share = shares.selectHistory(CircleShareObjectType.moment, moment, List.of(circle)).get(0);
        UUID target = newCircle();
        share.setId(UuidCreator.getTimeOrderedEpoch());
        share.setCircleId(target);

        var transaction = new TransactionTemplate(transactions);
        assertThrows(DuplicateKeyException.class,
                () -> transaction.executeWithoutResult(status -> shares.insertShares(List.of(share, share))));
        assertTrue(shares.selectHistory(CircleShareObjectType.moment, moment, List.of(target)).isEmpty());
    }

    @Test void permissionsAndPathIsolationAreEnforced() {
        UUID moment = newMoment(time, time);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.shareToCircles(request(CircleShareObjectType.moment, moment, List.of(circle)), viewer)).getStatus().value());
        service.shareToCircles(request(CircleShareObjectType.moment, moment, List.of(circle)), owner);
        var share = shares.selectHistory(CircleShareObjectType.moment, moment, List.of(circle)).get(0);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.browseShares(circle, stranger, new PageRequest<>(null, 20))).getStatus().value());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.withdrawShare(circle, share.getId(), viewer)).getStatus().value());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.withdrawShare(newCircle(), share.getId(), owner)).getStatus().value());
        assertEquals("SHARE_TYPE_MISMATCH", assertThrows(BusinessException.class, () -> service.browseSharedAlbumMoments(circle, share.getId(), viewer, new PageRequest<>(null, 20))).getCode());
        // 将有效成员设为创建者，验证创建者可以撤回他人分享。
        jdbc.update("UPDATE circle SET creator_user_id=? WHERE id=?", bytes(viewer), bytes(circle));
        service.withdrawShare(circle, share.getId(), viewer);
        assertNotNull(shares.selectHistoryById(circle, share.getId()).getDeletedAt());
    }

    @Test void feedUsesStrictDescendingCursorAndFiltersBeforeLimit() {
        UUID one = newMoment(time, time), two = newMoment(time, time), deleted = newMoment(time, time);
        for (UUID id : List.of(one, two, deleted)) {
            service.shareToCircles(request(CircleShareObjectType.moment, id, List.of(circle)), owner);
            var share = shares.selectHistory(CircleShareObjectType.moment, id, List.of(circle)).get(0);
            jdbc.update("UPDATE circle_share SET shared_at=? WHERE id=?", time, bytes(share.getId()));
        }
        moments.softDelete(deleted, time);
        var first = service.browseShares(circle, viewer, new PageRequest<>(null, 1));
        assertTrue(first.hasMore());
        assertFalse(first.items().get(0).canWithdraw());
        assertEquals("完整正文".repeat(60), first.items().get(0).moment().body());
        assertNull(first.items().get(0).album());
        var second = service.browseShares(circle, viewer, new PageRequest<>(first.nextCursor(), 1));
        assertFalse(second.hasMore());
        assertNull(second.nextCursor());
        assertNotEquals(first.items().get(0).id(), second.items().get(0).id());
        jdbc.update("UPDATE circle_member SET deleted_at=? WHERE circle_id=? AND user_id=?", time, bytes(circle), bytes(owner));
        assertTrue(service.browseShares(circle, viewer, new PageRequest<>(null, 20)).items().isEmpty());
    }

    @Test void sharedAlbumReturnsFullTimelineWithTwoFieldAscendingOrder() {
        UUID album = newAlbum();
        // 相同发生时间的创建时间故意反序，验证不沿用本人时光线的三字段排序。
        UUID one = newMoment(time, time.plusDays(1)), two = newMoment(time, time.minusDays(1));
        UUID removed = newMoment(time, time), deleted = newMoment(time, time);
        for (UUID id : List.of(one, two, removed, deleted)) associate(album, id);
        jdbc.update("UPDATE album_moment SET deleted_at=? WHERE album_id=? AND moment_id=?", time, bytes(album), bytes(removed));
        moments.softDelete(deleted, time);
        service.shareToCircles(request(CircleShareObjectType.album, album, List.of(circle)), owner);
        var feed = service.browseShares(circle, viewer, new PageRequest<>(null, 20));
        UUID shareId = feed.items().get(0).id();
        assertNull(feed.items().get(0).moment());
        assertEquals(2, feed.items().get(0).album().momentCount());
        assertNull(feed.items().get(0).album().coverUrl());
        var first = service.browseSharedAlbumMoments(circle, shareId, viewer, new PageRequest<>(null, 1));
        assertEquals(one, first.items().get(0).id());
        assertTrue(first.hasMore());
        var second = service.browseSharedAlbumMoments(circle, shareId, viewer, new PageRequest<>(first.nextCursor(), 1));
        assertEquals(two, second.items().get(0).id());
        assertFalse(second.hasMore());
        albums.softDelete(album, time);
        assertTrue(service.browseShares(circle, viewer, new PageRequest<>(null, 20)).items().isEmpty());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.browseSharedAlbumMoments(circle, shareId, viewer, new PageRequest<>(null, 20))).getStatus().value());
    }

    private CreateCircleSharesRequest request(CircleShareObjectType type, UUID id, List<UUID> targets) {
        return new CreateCircleSharesRequest(type, id, targets);
    }
    private UUID newCircle() {
        Circle row = new Circle(); row.setId(UuidCreator.getTimeOrderedEpoch()); circleIds.add(row.getId());
        row.setCreatorUserId(owner); row.setName("测试圈子"); row.setInviteCode(inviteNumbers.allocate(row.getId()));
        row.setInviteEnabled(true); row.setCreatedAt(time); row.setUpdatedAt(time); circles.insert(row);
        for (UUID user : List.of(owner, viewer)) {
            CircleMember member = new CircleMember(); member.setId(UuidCreator.getTimeOrderedEpoch());
            member.setCircleId(row.getId()); member.setUserId(user); member.setCreatedAt(time); member.setUpdatedAt(time); members.insert(member);
        }
        return row.getId();
    }
    private UUID newMoment(LocalDateTime memory, LocalDateTime created) {
        Moment row = new Moment(); row.setId(UuidCreator.getTimeOrderedEpoch()); momentIds.add(row.getId());
        row.setAuthorUserId(owner); row.setBody("完整正文".repeat(60)); row.setMemoryTime(memory); row.setCreatedAt(created); row.setUpdatedAt(created);
        moments.insert(row); return row.getId();
    }
    private UUID newAlbum() {
        Album row = new Album(); row.setId(UuidCreator.getTimeOrderedEpoch()); albumIds.add(row.getId());
        row.setOwnerUserId(owner); row.setName("共享时光"); row.setDescription("完整简介"); row.setOrganizeMode(AlbumOrganizeMode.manual);
        row.setCreatedAt(time); row.setUpdatedAt(time); albums.insert(row); return row.getId();
    }
    private void associate(UUID album, UUID moment) {
        AlbumMoment row = new AlbumMoment(); row.setId(UuidCreator.getTimeOrderedEpoch()); row.setAlbumId(album); row.setMomentId(moment);
        row.setCreatedAt(time); row.setUpdatedAt(time); associations.insert(row);
    }
    private byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
}
