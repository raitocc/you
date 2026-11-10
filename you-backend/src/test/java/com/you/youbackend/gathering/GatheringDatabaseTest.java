package com.you.youbackend.gathering;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.gathering.entity.GatheringMember;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.GatheringStatus;
import com.you.youbackend.gathering.request.AddGatheringMomentsRequest;
import com.you.youbackend.gathering.request.CreateGatheringRequest;
import com.you.youbackend.gathering.request.GatheringJoinPreviewRequest;
import com.you.youbackend.gathering.request.JoinGatheringRequest;
import com.you.youbackend.gathering.request.RemoveGatheringMomentsRequest;
import com.you.youbackend.gathering.request.UpdateGatheringRequest;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.media.mapper.MediaFileMapper;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.mapper.UserMapper;
import com.you.youbackend.user.model.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 使用配置中的 MySQL 验证 UUID/布尔映射、创建者资料及真实事务回滚；媒体签名模拟。 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class GatheringDatabaseTest {
    @Autowired private GatheringService service;
    @Autowired private MomentService momentService;
    @Autowired private MediaFileMapper files;
    @Autowired private GatheringMapper gatherings;
    @MockitoSpyBean private GatheringMemberMapper members;
    @Autowired private UserMapper users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MomentMapper moments;
    @MockitoBean private MediaService media;

    @Test
    @Transactional
    void persistsBothRecordsAndReturnsCreatorPublicProfile() {
        // 创建随机测试账号，测试结束时由外层测试事务自动回滚。
        AppUser user = saveUser();
        when(media.getReadUrl(user.getAvatarFileId())).thenReturn("https://example.test/avatar");
        var response = service.createGathering(new CreateGatheringRequest("  出游  ", "介绍"), user.getId());
        var saved = gatherings.selectById(response.id());

        assertEquals(user.getId(), saved.getCreatorUserId());
        assertEquals("出游", saved.getName());
        assertEquals("介绍", saved.getDescription());
        assertEquals(GatheringStatus.ONGOING, saved.getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM gathering WHERE id = ?",
                Integer.class, bytes(response.id())));
        // 同时验证另一枚举值的写入和读取，数据库保存显式 code 而非名称或序号。
        saved.setStatus(GatheringStatus.ENDED);
        gatherings.updateById(saved);
        assertEquals(GatheringStatus.ENDED, gatherings.selectById(response.id()).getStatus());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM gathering WHERE id = ?",
                Integer.class, bytes(response.id())));
        assertTrue(saved.getInviteEnabled());
        assertEquals(response.inviteCode(), saved.getInviteCode());
        assertEquals(saved.getCreatedAt().toInstant(ZoneOffset.UTC), response.createdAt());
        assertEquals("测试昵称", response.creator().nickname());
        assertEquals("https://example.test/avatar", response.creator().avatarUrl());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM gathering_member WHERE gathering_id = ? AND user_id = ? AND deleted_at IS NULL",
                Integer.class, bytes(response.id()), bytes(user.getId())));
    }

    @Test
    void rollsBackGatheringWhenMembershipInsertFails() {
        // 此测试使用真实 Service 代理事务，外层保持无事务以观察失败后的数据库状态。
        AppUser user = saveUser();
        doThrow(new IllegalStateException("模拟成员保存失败")).when(members).insert(any(GatheringMember.class));
        try {
            assertThrows(IllegalStateException.class, () -> service.createGathering(
                    new CreateGatheringRequest("事务回滚测试", null), user.getId()));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM gathering WHERE creator_user_id = ?", Integer.class, bytes(user.getId())));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM gathering_member WHERE user_id = ?", Integer.class, bytes(user.getId())));
        } finally {
            // 仅清理本测试随机账号及其创建的记录，即便断言失败也保留其他业务数据。
            jdbc.update("DELETE FROM gathering_member WHERE user_id = ?", bytes(user.getId()));
            jdbc.update("DELETE FROM gathering WHERE creator_user_id = ?", bytes(user.getId()));
            jdbc.update("DELETE FROM app_user WHERE id = ?", bytes(user.getId()));
        }
    }

    @Test
    @Transactional
    void previewsWithoutJoiningThenJoinsWithRealCountsAndPreservesRepeatedMembership() {
        AppUser creator = saveUser(), guest = saveUser();
        var gathering = service.createGathering(new CreateGatheringRequest("邀请测试", null), creator.getId());
        UUID momentId = UuidCreator.getTimeOrderedEpoch();
        // 用已有数据库字段准备一条共享瞬间，确认加入响应读取真实数量。
        jdbc.update("""
                INSERT INTO moment (id, author_user_id, gathering_id, body, memory_time, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, bytes(momentId), bytes(creator.getId()), bytes(gathering.id()), "共享内容",
                creator.getCreatedAt(), creator.getCreatedAt(), creator.getCreatedAt());

        var preview = service.previewJoin(new GatheringJoinPreviewRequest(gathering.inviteCode()), guest.getId());
        assertFalse(preview.isMember());
        assertEquals(1, members.countActiveMembers(gathering.id()));
        var joined = service.joinGathering(new JoinGatheringRequest(gathering.inviteCode()), guest.getId());
        assertEquals(2, joined.memberCount());
        assertEquals(1, joined.momentCount());
        assertEquals(creator.getId(), joined.creator().id());

        GatheringMember first = findMember(gathering.id(), guest.getId());
        assertEquals(7, first.getId().version());
        assertTrue(service.previewJoin(new GatheringJoinPreviewRequest(gathering.inviteCode()), guest.getId()).isMember());
        service.joinGathering(new JoinGatheringRequest(gathering.inviteCode()), guest.getId());
        // 直接再次执行 SQL，覆盖两个重叠请求都通过成员预检查时的唯一索引分支。
        GatheringMember duplicate = membershipCandidate(gathering.id(), guest.getId(), first.getCreatedAt().plusDays(1));
        members.joinOrRestore(duplicate);
        GatheringMember repeated = findMember(gathering.id(), guest.getId());
        assertEquals(first.getId(), repeated.getId());
        assertEquals(first.getCreatedAt(), repeated.getCreatedAt());
        assertEquals(first.getUpdatedAt(), repeated.getUpdatedAt());
        assertEquals(2, members.countActiveMembers(gathering.id()));

        jdbc.update("UPDATE moment SET deleted_at = ? WHERE id = ?", creator.getCreatedAt(), bytes(momentId));
        assertEquals(0, service.joinGathering(new JoinGatheringRequest(gathering.inviteCode()), guest.getId()).momentCount());
    }

    @Test
    @Transactional
    void rejoinRestoresOriginalRelationAndLeavesMomentAssociationsUnchanged() {
        AppUser creator = saveUser(), guest = saveUser();
        var gathering = service.createGathering(new CreateGatheringRequest("恢复测试", null), creator.getId());
        LocalDateTime originalTime = guest.getCreatedAt().minusDays(1);
        GatheringMember old = membershipCandidate(gathering.id(), guest.getId(), originalTime);
        old.setDeletedAt(originalTime);
        members.insert(old);
        UUID privateMoment = UuidCreator.getTimeOrderedEpoch();
        jdbc.update("""
                INSERT INTO moment (id, author_user_id, body, memory_time, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, bytes(privateMoment), bytes(guest.getId()), "本人原记录",
                originalTime, originalTime, originalTime);

        assertFalse(service.previewJoin(new GatheringJoinPreviewRequest(gathering.inviteCode()), guest.getId()).isMember());
        var joined = service.joinGathering(new JoinGatheringRequest(gathering.inviteCode()), guest.getId());
        GatheringMember restored = findMember(gathering.id(), guest.getId());
        assertEquals(old.getId(), restored.getId());
        assertEquals(originalTime, restored.getCreatedAt());
        assertTrue(restored.getUpdatedAt().isAfter(originalTime));
        assertNull(restored.getDeletedAt());
        assertEquals(2, joined.memberCount());
        assertEquals(0, joined.momentCount());
        assertNull(jdbc.queryForObject("SELECT gathering_id FROM moment WHERE id = ?", byte[].class, bytes(privateMoment)));
    }

    @Test
    @Transactional
    void rechecksInvitationAtConfirmationAndAllowsEndedGathering() {
        AppUser creator = saveUser(), guest = saveUser();
        var created = service.createGathering(new CreateGatheringRequest("重新校验测试", null), creator.getId());
        var previewRequest = new GatheringJoinPreviewRequest(created.inviteCode());
        var joinRequest = new JoinGatheringRequest(created.inviteCode());
        service.previewJoin(previewRequest, guest.getId());
        var gathering = gatherings.selectById(created.id());
        gathering.setInviteEnabled(false);
        gatherings.updateById(gathering);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.joinGathering(joinRequest, guest.getId())).getStatus());
        assertFalse(members.isActiveMember(created.id(), guest.getId()));

        gathering.setInviteEnabled(true);
        gathering.setStatus(GatheringStatus.ENDED);
        gatherings.updateById(gathering);
        assertEquals(created.id(), service.joinGathering(joinRequest, guest.getId()).id());
        gatherings.deleteById(created.id());
        assertThrows(BusinessException.class, () -> service.previewJoin(previewRequest, guest.getId()));
        assertThrows(BusinessException.class, () -> service.joinGathering(joinRequest, guest.getId()));
    }

    @Test
    @Transactional
    void pagesOwnGatheringsAndCountsOnlyEffectiveRecords() {
        // 固定同一创建时间，验证 UUID 次序及游标边界，其他同行和已退出、解散记录均排除。
        AppUser owner = saveUser(), other = saveUser();
        var first = service.createGathering(new CreateGatheringRequest("第一条", null), owner.getId());
        var second = service.createGathering(new CreateGatheringRequest("第二条", "😀".repeat(101)), owner.getId());
        var left = service.createGathering(new CreateGatheringRequest("已退出", null), other.getId());
        service.joinGathering(new JoinGatheringRequest(left.inviteCode()), owner.getId());
        members.deleteById(findMember(left.id(), owner.getId()).getId());
        var dissolved = service.createGathering(new CreateGatheringRequest("已解散", null), owner.getId());
        gatherings.deleteById(dissolved.id());
        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0);
        jdbc.update("UPDATE gathering SET created_at = ? WHERE id IN (?, ?)", time, bytes(first.id()), bytes(second.id()));
        service.joinGathering(new JoinGatheringRequest(second.inviteCode()), other.getId());
        UUID moment = UuidCreator.getTimeOrderedEpoch();
        jdbc.update("""
                INSERT INTO moment (id, author_user_id, gathering_id, body, memory_time, created_at, updated_at)
                VALUES (?, ?, ?, '共享', ?, ?, ?)
                """, bytes(moment), bytes(owner.getId()), bytes(second.id()), time, time, time);
        UUID removedMoment = UuidCreator.getTimeOrderedEpoch();
        jdbc.update("""
                INSERT INTO moment (id, author_user_id, gathering_id, body, memory_time, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, '已删', ?, ?, ?, ?)
                """, bytes(removedMoment), bytes(owner.getId()), bytes(second.id()), time, time, time, time);
        var page = service.browseGatherings(new PageRequest<>(null, 1), owner.getId());
        assertEquals(second.id(), page.items().get(0).id());
        assertEquals(2, page.items().get(0).memberCount());
        assertEquals(1, page.items().get(0).momentCount());
        assertEquals("😀".repeat(100), page.items().get(0).descriptionPreview());
        assertTrue(page.hasMore());
        var next = service.browseGatherings(new PageRequest<>(page.nextCursor(), 1), owner.getId());
        assertEquals(first.id(), next.items().get(0).id());
        assertFalse(next.hasMore());
        assertNull(next.nextCursor());
    }

    @Test
    @Transactional
    void pagesMembersAndUpdatesOnlyBasicInformation() {
        // 关系同时间按用户 ID 排序，退出关系排除；编辑显式清空介绍且保留邀请、状态、短码。
        AppUser owner = saveUser(), guest = saveUser(), left = saveUser();
        var created = service.createGathering(new CreateGatheringRequest("原名", "介绍"), owner.getId());
        service.joinGathering(new JoinGatheringRequest(created.inviteCode()), guest.getId());
        service.joinGathering(new JoinGatheringRequest(created.inviteCode()), left.getId());
        members.deleteById(findMember(created.id(), left.getId()).getId());
        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0);
        jdbc.update("UPDATE gathering_member SET created_at = ? WHERE gathering_id = ?", time, bytes(created.id()));
        var page = service.browseMembers(created.id(), new PageRequest<>(null, 1), owner.getId());
        assertEquals(owner.getId(), page.items().get(0).id());
        assertTrue(page.hasMore());
        var next = service.browseMembers(created.id(), new PageRequest<>(page.nextCursor(), 1), owner.getId());
        assertEquals(guest.getId(), next.items().get(0).id());
        assertFalse(next.hasMore());
        var gathering = gatherings.selectById(created.id());
        gathering.setStatus(GatheringStatus.ENDED);
        gathering.setInviteEnabled(false);
        gathering.setFaceCode("2005");
        gatherings.updateById(gathering);
        service.updateGathering(created.id(), new UpdateGatheringRequest(" 新名 ", null), owner.getId());
        var saved = gatherings.selectById(created.id());
        assertEquals("新名", saved.getName());
        assertNull(saved.getDescription());
        assertEquals(GatheringStatus.ENDED, saved.getStatus());
        assertFalse(saved.getInviteEnabled());
        assertEquals("2005", saved.getFaceCode());
        assertEquals(created.inviteCode(), saved.getInviteCode());
        assertEquals(created.createdAt(), saved.getCreatedAt().toInstant(ZoneOffset.UTC));
        assertEquals(2, service.getGathering(created.id(), guest.getId()).memberCount());
    }

    @Test
    @Transactional
    void batchAssociationIsIdempotentAndRemovalKeepsOtherGatheringAndContent() {
        // 以真实 SQL 验证三种关联状态，重复请求保持修改时间，取消仅影响指定同行。
        AppUser owner = saveUser();
        var target = service.createGathering(new CreateGatheringRequest("目标", null), owner.getId());
        var other = service.createGathering(new CreateGatheringRequest("其他", null), owner.getId());
        UUID pending = saveMoment(owner.getId(), null);
        UUID existing = saveMoment(owner.getId(), target.id());
        UUID elsewhere = saveMoment(owner.getId(), other.id());
        LocalDateTime originalTime = moments.selectById(existing).getUpdatedAt();
        service.addMoments(target.id(), new AddGatheringMomentsRequest(List.of(pending, existing, pending)), owner.getId());
        assertEquals(target.id(), moments.selectById(pending).getGatheringId());
        assertEquals(originalTime, moments.selectById(existing).getUpdatedAt());
        LocalDateTime linkedAt = moments.selectById(pending).getUpdatedAt();
        service.addMoments(target.id(), new AddGatheringMomentsRequest(List.of(pending, existing)), owner.getId());
        assertEquals(linkedAt, moments.selectById(pending).getUpdatedAt());
        var savedTarget = gatherings.selectById(target.id());
        savedTarget.setStatus(GatheringStatus.ENDED);
        gatherings.updateById(savedTarget);
        service.removeMoments(target.id(), new RemoveGatheringMomentsRequest(List.of(pending, existing, elsewhere)), owner.getId());
        assertNull(moments.selectById(pending).getGatheringId());
        assertNull(moments.selectById(existing).getGatheringId());
        assertEquals(other.id(), moments.selectById(elsewhere).getGatheringId());
        assertEquals("原始内容", moments.selectById(pending).getBody());
        LocalDateTime removedAt = moments.selectById(pending).getUpdatedAt();
        service.removeMoments(target.id(), new RemoveGatheringMomentsRequest(List.of(pending, elsewhere)), owner.getId());
        assertEquals(removedAt, moments.selectById(pending).getUpdatedAt());
    }

    @Test
    @Transactional
    void batchAssociationRejectsConflictsAndInvalidOwnershipWithoutPartialWrites() {
        // 所有拒绝发生在写入之前：其他同行、其他作者、已删除及不存在记录均使整批失败。
        AppUser owner = saveUser(), stranger = saveUser();
        var target = service.createGathering(new CreateGatheringRequest("目标", null), owner.getId());
        var other = service.createGathering(new CreateGatheringRequest("其他", null), owner.getId());
        UUID pending = saveMoment(owner.getId(), null);
        UUID elsewhere = saveMoment(owner.getId(), other.id());
        var conflict = assertThrows(BusinessException.class, () -> service.addMoments(target.id(),
                new AddGatheringMomentsRequest(List.of(pending, elsewhere)), owner.getId()));
        assertEquals("MOMENT_ALREADY_ASSOCIATED", conflict.getCode());
        assertNull(moments.selectById(pending).getGatheringId());
        UUID foreign = saveMoment(stranger.getId(), null);
        UUID deleted = saveMoment(owner.getId(), null);
        moments.deleteById(deleted);
        for (UUID invalid : List.of(foreign, deleted, UuidCreator.getTimeOrderedEpoch())) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class, () -> service.addMoments(target.id(),
                    new AddGatheringMomentsRequest(List.of(pending, invalid)), owner.getId())).getStatus());
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class, () -> service.removeMoments(target.id(),
                    new RemoveGatheringMomentsRequest(List.of(elsewhere, invalid)), owner.getId())).getStatus());
        }
        assertNull(moments.selectById(pending).getGatheringId());
        assertEquals(other.id(), moments.selectById(elsewhere).getGatheringId());
    }

    @Test
    @Transactional
    void readsSharedTimelineAndCrossAuthorImagesWithRealPermissionQueries() {
        // 同一发生时间按 ID 正序分页；非成员、已删除及取消关联均不能通过同行详情读取。
        AppUser creator = saveUser(), guest = saveUser(), stranger = saveUser();
        var target = service.createGathering(new CreateGatheringRequest("共享", null), creator.getId());
        service.joinGathering(new JoinGatheringRequest(target.inviteCode()), guest.getId());
        UUID first = saveMoment(creator.getId(), target.id());
        UUID second = saveMoment(guest.getId(), target.id());
        UUID removed = saveMoment(creator.getId(), target.id());
        moments.deleteById(removed);
        saveMoment(creator.getId(), null);
        UUID fileId = UuidCreator.getTimeOrderedEpoch();
        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0);
        jdbc.update("""
                INSERT INTO media_file (id, owner_user_id, storage_key, mime_type, byte_size, created_at, updated_at)
                VALUES (?, ?, 'shared-test/image', 'image/jpeg', 10, ?, ?)
                """, bytes(fileId), bytes(creator.getId()), time, time);
        jdbc.update("""
                INSERT INTO moment_image (id, moment_id, file_id, sort_order, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?)
                """, bytes(UuidCreator.getTimeOrderedEpoch()), bytes(first), bytes(fileId), time, time);
        // 验证底层查询不限定查看者，删除文件被过滤，查询结果仅返回有效图片。
        var keys = files.selectImageStorageKeys(List.of(fileId), List.of("image/jpeg"));
        assertEquals(1, keys.size());
        assertEquals(fileId, keys.get(0).getId());
        when(media.findImageKeys(List.of(fileId))).thenReturn(java.util.Map.of(fileId, keys.get(0).getStorageKey()));
        when(media.createReadUrl("shared-test/image")).thenReturn("signed-shared-image");
        var page = momentService.browseGatheringMoments(target.id(), new PageRequest<>(null, 1), guest.getId());
        assertEquals(first, page.items().get(0).id());
        assertEquals(creator.getId(), page.items().get(0).author().id());
        assertEquals("signed-shared-image", page.items().get(0).coverUrl());
        assertTrue(page.hasMore());
        var next = momentService.browseGatheringMoments(target.id(), new PageRequest<>(page.nextCursor(), 1), guest.getId());
        assertEquals(second, next.items().get(0).id());
        assertFalse(next.hasMore());
        assertEquals("signed-shared-image", momentService.getGatheringMoment(target.id(), first, guest.getId()).images().get(0).url());
        assertThrows(BusinessException.class, () -> momentService.getGatheringMoment(target.id(), first, stranger.getId()));
        assertThrows(BusinessException.class, () -> momentService.getGatheringMoment(target.id(), removed, guest.getId()));
        service.removeMoments(target.id(), new RemoveGatheringMomentsRequest(List.of(first)), creator.getId());
        assertThrows(BusinessException.class, () -> momentService.getGatheringMoment(target.id(), first, creator.getId()));
        assertEquals(first, momentService.getMoment(first, creator.getId()).id());
        files.deleteById(fileId);
        assertTrue(files.selectImageStorageKeys(List.of(fileId), List.of("image/jpeg")).isEmpty());
    }

    /** 准备固定审计时间的瞬间，便于验证重复操作与正文保留。 */
    private UUID saveMoment(UUID author, UUID gatheringId) {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0);
        jdbc.update("""
                INSERT INTO moment (id, author_user_id, gathering_id, body, memory_time, created_at, updated_at)
                VALUES (?, ?, ?, '原始内容', ?, ?, ?)
                """, bytes(id), bytes(author), gatheringId == null ? null : bytes(gatheringId), time, time, time);
        return id;
    }

    /** 读取测试中的有效成员关系，便于比较恢复前后的审计字段。 */
    private GatheringMember findMember(UUID gatheringId, UUID userId) {
        return members.selectOne(Wrappers.<GatheringMember>lambdaQuery()
                .eq(GatheringMember::getGatheringId, gatheringId).eq(GatheringMember::getUserId, userId));
    }

    /** 准备不同 ID 与时间的候选关系，验证唯一索引冲突时保留原记录。 */
    private GatheringMember membershipCandidate(UUID gatheringId, UUID userId, LocalDateTime time) {
        GatheringMember member = new GatheringMember();
        member.setId(UuidCreator.getTimeOrderedEpoch());
        member.setGatheringId(gatheringId);
        member.setUserId(userId);
        member.setCreatedAt(time);
        member.setUpdatedAt(time);
        return member;
    }
    /** 创建独立的随机测试账号，头像仅用于验证签名方法的调用。 */
    private AppUser saveUser() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        AppUser user = new AppUser();
        user.setId(UuidCreator.getTimeOrderedEpoch());
        user.setUsername("gathering_test_" + user.getId());
        user.setPasswordHash("test-only");
        user.setNickname("测试昵称");
        user.setAvatarFileId(UuidCreator.getTimeOrderedEpoch());
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        users.insert(user);
        return user;
    }

    /** 将测试查询参数编码为与全局 TypeHandler 相同顺序的 UUID 字节。 */
    private byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
