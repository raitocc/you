package com.you.youbackend.inbox;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.model.AlbumOrganizeMode;
import com.you.youbackend.circle.entity.Circle;
import com.you.youbackend.circle.entity.CircleMember;
import com.you.youbackend.circle.mapper.*;
import com.you.youbackend.circle.model.CircleShareObjectType;
import com.you.youbackend.circle.request.CreateCircleSharesRequest;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.gathering.entity.GatheringMember;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.request.*;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.inbox.entity.UserInbox;
import com.you.youbackend.inbox.listener.InboxEventListener;
import com.you.youbackend.inbox.mapper.UserInboxMapper;
import com.you.youbackend.inbox.model.*;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.request.CreateMomentRequest;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL 验证业务事件、投递范围、历史失效与原业务事务回滚。 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class InboxDeliveryDatabaseTest {
    @Autowired GatheringService gatherings;
    @Autowired CircleService circles;
    @Autowired MomentService momentService;
    @Autowired MomentMapper moments;
    @Autowired CircleMapper circleMapper;
    @Autowired CircleMemberMapper circleMembers;
    @Autowired CircleShareMapper shares;
    @Autowired GatheringMemberMapper gatheringMembers;
    @Autowired AlbumMapper albums;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired UserInboxMapper inbox;
    @MockitoSpyBean InboxEventListener listener;
    @MockitoBean UserService users;
    @MockitoBean MediaService media;
    final UUID owner = UuidCreator.getTimeOrderedEpoch();
    final UUID guest = UuidCreator.getTimeOrderedEpoch();
    final LocalDateTime oldTime = LocalDateTime.of(2026, 1, 1, 0, 0);

    @BeforeEach void profiles() {
        when(users.getPublicUser(any())).thenAnswer(call -> new PublicUserResponse(call.getArgument(0), "测试用户", null));
    }

    @Test @Transactional
    void gatheringDeliversNewLinksToCurrentMembersIncludingActor() {
        UUID gathering = newGathering();
        addGatheringMember(gathering, guest);
        UUID former = UuidCreator.getTimeOrderedEpoch();
        addGatheringMember(gathering, former);
        gatheringMembers.leaveGathering(gatheringMembers.selectIncludingDeleted(gathering, former).getId(), oldTime);
        UUID first = newMoment(owner), second = newMoment(owner);
        var request = new AddGatheringMomentsRequest(List.of(first, second, first));
        gatherings.addMoments(gathering, request, owner);
        List<UserInbox> delivered = rows(gathering);
        assertEquals(4, delivered.size());
        assertEquals(Set.of(owner, guest), new HashSet<>(delivered.stream().map(UserInbox::getRecipientUserId).toList()));
        assertTrue(delivered.stream().allMatch(row -> row.getId().version() == 7 && row.getShareId() == null
                && row.getSourceType() == InboxSourceType.gathering && row.getObjectType() == InboxObjectType.moment));
        assertEquals(moments.selectById(first).getUpdatedAt(), delivered.get(0).getSharedAt());
        assertNotEquals(oldTime, delivered.get(0).getSharedAt());
        Set<UUID> originalIds = new HashSet<>(delivered.stream().map(UserInbox::getId).toList());
        gatherings.addMoments(gathering, request, owner);
        assertEquals(originalIds, new HashSet<>(rows(gathering).stream().map(UserInbox::getId).toList()));
        // 新加入者只接收此后动态，旧关联保持原接收范围。
        UUID newcomer = UuidCreator.getTimeOrderedEpoch();
        addGatheringMember(gathering, newcomer);
        assertTrue(rows(gathering).stream().noneMatch(row -> newcomer.equals(row.getRecipientUserId())));
        gatherings.addMoments(gathering, new AddGatheringMomentsRequest(List.of(newMoment(owner))), owner);
        assertEquals(7, rows(gathering).size());
    }

    @Test @Transactional
    void publishingWithGatheringCreatesInboxInSameFlow() {
        UUID gathering = newGathering();
        addGatheringMember(gathering, guest);
        UUID id = UuidCreator.getTimeOrderedEpoch();
        momentService.createMoment(new CreateMomentRequest(id, "标题", "正文", List.of(), List.of(), null,
                Instant.parse("2026-01-01T00:00:00Z"), null, gathering), owner);
        assertEquals(2, rows(gathering).size());
        assertTrue(rows(gathering).stream().allMatch(row -> id.equals(row.getObjectId())));
    }

    @Test @Transactional
    void circleKeepsIndependentSourcesAndRestoredSharesCreateFreshCards() {
        UUID first = newCircle(), second = newCircle(), moment = newMoment(owner);
        var request = new CreateCircleSharesRequest(CircleShareObjectType.moment, moment, List.of(first, second));
        circles.shareToCircles(request, owner);
        List<UserInbox> original = rows(first);
        UUID share = original.get(0).getShareId();
        assertEquals(2, original.size());
        assertEquals(2, rows(second).size());
        assertTrue(original.stream().allMatch(row -> share.equals(row.getShareId()) && owner.equals(row.getActorUserId())));
        assertEquals(shares.selectHistoryById(first, share).getSharedAt(), original.get(0).getSharedAt());
        circles.shareToCircles(request, owner);
        assertEquals(2, rows(first).size());
        circles.withdrawShare(first, share, owner);
        circles.withdrawShare(first, share, owner);
        assertTrue(rows(first).isEmpty());
        assertEquals(2, rows(second).size());
        circles.shareToCircles(request, owner);
        assertEquals(2, rows(first).size());
        assertTrue(rows(first).stream().noneMatch(row -> original.stream().anyMatch(old -> old.getId().equals(row.getId()))));
        assertEquals(4, historyCount(first));
        assertEquals(2, historyCount(second));
    }

    @Test @Transactional
    void albumShareCreatesAlbumCardsOnly() {
        UUID circle = newCircle();
        Album album = new Album();
        album.setId(UuidCreator.getTimeOrderedEpoch());
        album.setOwnerUserId(owner);
        album.setName("时光");
        album.setOrganizeMode(AlbumOrganizeMode.manual);
        album.setCreatedAt(oldTime);
        album.setUpdatedAt(oldTime);
        albums.insert(album);
        circles.shareToCircles(new CreateCircleSharesRequest(CircleShareObjectType.album, album.getId(), List.of(circle)), owner);
        assertEquals(2, rows(circle).size());
        assertTrue(rows(circle).stream().allMatch(row -> row.getObjectType() == InboxObjectType.album
                && album.getId().equals(row.getObjectId())));
    }

    @Test @Transactional
    void unlinkAndLeavingInvalidateOutboundCardsAndRetainReceivedHistory() {
        UUID gathering = newGathering();
        addGatheringMember(gathering, guest);
        UUID ownMoment = newMoment(owner), guestMoment = newMoment(guest);
        gatherings.addMoments(gathering, new AddGatheringMomentsRequest(List.of(ownMoment)), owner);
        gatherings.addMoments(gathering, new AddGatheringMomentsRequest(List.of(guestMoment)), guest);
        gatherings.leaveGathering(gathering, guest);
        assertEquals(2, rows(gathering).size());
        assertTrue(rows(gathering).stream().allMatch(row -> row.getActorUserId().equals(owner)));
        assertTrue(rows(gathering).stream().anyMatch(row -> row.getRecipientUserId().equals(guest)));
        assertEquals(4, historyCount(gathering));
        gatherings.removeMoments(gathering, new RemoveGatheringMomentsRequest(List.of(ownMoment)), owner);
        assertTrue(rows(gathering).isEmpty());
        gatherings.addMoments(gathering, new AddGatheringMomentsRequest(List.of(ownMoment)), owner);
        assertEquals(1, rows(gathering).size());
        assertEquals(5, historyCount(gathering));
    }

    @Test @Transactional
    void circleLeaveAndRemovalRetainIncomingRowsAndInvalidateOutgoingShares() {
        for (boolean removed : List.of(false, true)) {
            UUID circle = newCircle();
            circles.shareToCircles(new CreateCircleSharesRequest(CircleShareObjectType.moment, newMoment(owner), List.of(circle)), owner);
            circles.shareToCircles(new CreateCircleSharesRequest(CircleShareObjectType.moment, newMoment(guest), List.of(circle)), guest);
            if (removed) circles.removeMember(circle, guest, owner);
            else circles.leaveCircle(circle, guest);
            assertEquals(2, rows(circle).size());
            assertTrue(rows(circle).stream().allMatch(row -> row.getActorUserId().equals(owner)));
            assertTrue(rows(circle).stream().anyMatch(row -> row.getRecipientUserId().equals(guest)));
            assertEquals(4, historyCount(circle));
        }
    }

    @Test
    void listenerFailureRollsBackSourceShareAndDeliveredInbox() {
        UUID moment = UuidCreator.getTimeOrderedEpoch();
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("模拟投递后监听失败");
        }).when(listener).onCircleContentsShared(any());
        var transaction = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            // 预置数据、源分享和真实投递同处一个事务，监听异常后全部回滚。
            UUID circle = newCircle();
            Moment row = momentEntity(moment, owner); moments.insert(row);
            circles.shareToCircles(new CreateCircleSharesRequest(CircleShareObjectType.moment, moment, List.of(circle)), owner);
        }));
        assertNull(moments.selectById(moment));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_inbox WHERE object_id=?", Integer.class, bytes(moment)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM circle_share WHERE object_id=?", Integer.class, bytes(moment)));
    }

    /** 通过真实业务创建同行，成员与状态沿用既有默认值。 */
    private UUID newGathering() {
        return gatherings.createGathering(new CreateGatheringRequest("测试同行", null), owner).id();
    }

    /** 建立当前有效成员；测试中用于控制接收范围。 */
    private void addGatheringMember(UUID gathering, UUID user) {
        GatheringMember member = new GatheringMember();
        member.setId(UuidCreator.getTimeOrderedEpoch()); member.setGatheringId(gathering); member.setUserId(user);
        member.setCreatedAt(oldTime); member.setUpdatedAt(oldTime); gatheringMembers.insert(member);
    }

    /** 建立包含两名有效成员的圈子，沿用项目邀请码生成方式。 */
    private UUID newCircle() {
        Circle row = new Circle();
        row.setId(UuidCreator.getTimeOrderedEpoch()); row.setCreatorUserId(owner); row.setName("测试圈子");
        row.setInviteCode(com.you.youbackend.common.util.InviteCodeGenerator.generate()); row.setInviteEnabled(true);
        row.setCreatedAt(oldTime); row.setUpdatedAt(oldTime); circleMapper.insert(row);
        for (UUID user : List.of(owner, guest)) {
            CircleMember member = new CircleMember();
            member.setId(UuidCreator.getTimeOrderedEpoch()); member.setCircleId(row.getId()); member.setUserId(user);
            member.setCreatedAt(oldTime); member.setUpdatedAt(oldTime); circleMembers.insert(member);
        }
        return row.getId();
    }

    /** 保存一个尚无同行关联的有效瞬间。 */
    private UUID newMoment(UUID author) {
        Moment row = momentEntity(UuidCreator.getTimeOrderedEpoch(), author);
        moments.insert(row);
        return row.getId();
    }

    /** 使用旧发生时间，检验分享排序时间与经历发生时间分别保存。 */
    private Moment momentEntity(UUID id, UUID author) {
        Moment row = new Moment(); row.setId(id); row.setAuthorUserId(author); row.setBody("正文");
        row.setMemoryTime(oldTime); row.setCreatedAt(oldTime); row.setUpdatedAt(oldTime);
        return row;
    }

    /** 查询当前来源的有效动态，读取实际实体映射结果。 */
    private List<UserInbox> rows(UUID source) {
        return inbox.selectList(Wrappers.<UserInbox>lambdaQuery().eq(UserInbox::getSourceId, source));
    }

    /** 统计有效与失效的全部历史，检验恢复分享使用新动态。 */
    private int historyCount(UUID source) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_inbox WHERE source_id=?", Integer.class, bytes(source));
    }

    /** 按项目统一 UUID 字节顺序传递原生 SQL 参数。 */
    private byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
