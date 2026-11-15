package com.you.youbackend.inbox;

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
import com.you.youbackend.inbox.model.*;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.inbox.service.RelatedService;
import com.you.youbackend.inbox.response.RelatedResponse;
import com.you.youbackend.circle.request.JoinCircleRequest;
import com.you.youbackend.album.entity.AlbumMoment;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.user.filter.JwtAuthFilter;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** 通过真实业务投递和 MySQL 查询验证 Related 可访问性、分页与摘要。测试数据统一回滚。 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Transactional
class RelatedDatabaseTest {
    @Autowired RelatedService related;
    @Autowired GatheringService gatherings;
    @Autowired CircleService circles;
    @Autowired MomentMapper moments;
    @Autowired CircleMapper circleMapper;
    @Autowired CircleMemberMapper circleMembers;
    @Autowired CircleShareMapper shares;
    @Autowired GatheringMemberMapper gatheringMembers;
    @Autowired com.you.youbackend.gathering.mapper.GatheringMapper gatheringMapper;
    @Autowired AlbumMapper albums;
    @Autowired AlbumMomentMapper albumMoments;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @MockitoBean UserService users;
    @MockitoBean MediaService media;
    final UUID owner = UuidCreator.getTimeOrderedEpoch(), guest = UuidCreator.getTimeOrderedEpoch();
    final LocalDateTime oldTime = LocalDateTime.of(2026, 1, 1, 0, 0);

    @BeforeEach void profiles() {
        when(users.getPublicUser(any())).thenAnswer(call -> new PublicUserResponse(call.getArgument(0), "测试用户", null));
        when(users.getPublicUsers(anyCollection())).thenAnswer(call -> {
            Map<UUID, PublicUserResponse> result = new HashMap<>();
            for (UUID id : (Collection<UUID>) call.getArgument(0)) result.put(id, new PublicUserResponse(id, "测试用户", null));
            return result;
        });
    }

    @Test void mixedFeedRetainsSourcesAndPaginatesEqualSharingTimes() {
        UUID gathering = newGathering(), firstCircle = newCircle(), secondCircle = newCircle();
        addGatheringMember(gathering, guest);
        UUID moment = newMoment(owner), album = newAlbum(owner, "简介", null);
        gatherings.addMoments(gathering, new AddGatheringMomentsRequest(List.of(moment)), owner);
        shareMoment(firstCircle, moment, owner);
        shareMoment(secondCircle, moment, owner);
        circles.shareToCircles(new CreateCircleSharesRequest(CircleShareObjectType.album, album, List.of(firstCircle)), owner);
        LocalDateTime sharedAt = LocalDateTime.of(2026, 11, 14, 12, 0);
        jdbc.update("UPDATE user_inbox SET shared_at=? WHERE recipient_user_id=?", sharedAt, bytes(guest));
        var first = related.browseRelated(guest, new PageRequest<RelatedCursor>(null, 2));
        assertEquals(2, first.items().size()); assertTrue(first.hasMore());
        var second = related.browseRelated(guest, new PageRequest<>(first.nextCursor(), 2));
        assertEquals(2, second.items().size()); assertFalse(second.hasMore()); assertNull(second.nextCursor());
        List<RelatedResponse> all = new ArrayList<>(first.items()); all.addAll(second.items());
        assertEquals(4, new HashSet<>(all.stream().map(RelatedResponse::id).toList()).size());
        for (int i = 1; i < all.size(); i++) {
            assertTrue(Arrays.compareUnsigned(bytes(all.get(i-1).id()), bytes(all.get(i).id())) > 0);
        }
        assertTrue(all.stream().allMatch(row -> row.sharedAt().equals(sharedAt.toInstant(ZoneOffset.UTC))));
        assertEquals(3, all.stream().filter(row -> row.moment() != null && moment.equals(row.moment().id())).count());
        assertEquals(Set.of(gathering, firstCircle, secondCircle), new HashSet<>(all.stream().map(row -> row.source().id()).toList()));
        for (var card : all) {
            assertEquals(owner, card.actor().id());
            if (card.objectType() == InboxObjectType.moment) {
                assertNull(card.album()); assertEquals(owner, card.moment().author().id());
                assertEquals(oldTime.toInstant(ZoneOffset.UTC), card.moment().memoryTime());
            } else { assertNull(card.moment()); assertEquals(album, card.album().id()); }
            if (card.source().type() == InboxSourceType.circle) assertNotNull(card.shareId());
            else assertNull(card.shareId());
        }
        assertEquals(4, feed(owner).size());
        assertTrue(feed(UUID.randomUUID()).isEmpty());
    }

    @Test void invalidEntriesAreFilteredBeforeLimitAndSourceDissolutionHidesAll() {
        UUID circle = newCircle();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) { UUID id = newMoment(owner); ids.add(id); shareMoment(circle, id, owner); }
        // 删除原内容与撤回分享的动态在分页前过滤，仍有四条有效动态。
        moments.deleteById(ids.get(5));
        var share = shares.selectHistory(CircleShareObjectType.moment, ids.get(4), List.of(circle)).get(0);
        circles.withdrawShare(circle, share.getId(), owner);
        var first = related.browseRelated(guest, new PageRequest<RelatedCursor>(null, 2));
        assertEquals(2, first.items().size()); assertTrue(first.hasMore());
        var second = related.browseRelated(guest, new PageRequest<>(first.nextCursor(), 2));
        assertEquals(2, second.items().size()); assertFalse(second.hasMore());
        circles.dissolveCircle(circle, owner);
        assertTrue(feed(guest).isEmpty());
    }

    @Test void circleRejoinRestoresReceivedHistoryAndLeavesAwayPeriodEmpty() {
        UUID circle = newCircle(), old = newMoment(owner);
        String invite = circleMapper.selectById(circle).getInviteCode();
        shareMoment(circle, old, owner);
        var original = feed(guest).get(0);
        circles.leaveCircle(circle, guest);
        assertTrue(feed(guest).isEmpty());
        shareMoment(circle, newMoment(owner), owner);
        circles.join(new JoinCircleRequest(invite), guest);
        assertEquals(List.of(original.id()), feed(guest).stream().map(RelatedResponse::id).toList());
        assertEquals(original.sharedAt(), feed(guest).get(0).sharedAt());
        shareMoment(circle, newMoment(owner), owner);
        assertEquals(2, feed(guest).size());
        circles.removeMember(circle, guest, owner);
        assertTrue(feed(guest).isEmpty());
    }

    @Test void gatheringExitRejoinEndUnlinkAndDissolveFollowCurrentAccess() {
        UUID gathering = newGathering(); addGatheringMember(gathering, guest);
        UUID moment = newMoment(owner);
        gatherings.addMoments(gathering, new AddGatheringMomentsRequest(List.of(moment)), owner);
        UUID original = feed(guest).get(0).id();
        gatherings.leaveGathering(gathering, guest);
        assertTrue(feed(guest).isEmpty());
        // 恢复既有成员关系，确认已接收卡片恢复且沿用原 ID。
        gatherings.joinGathering(new JoinGatheringRequest(gatheringMapper.selectById(gathering).getInviteCode()), guest);
        assertEquals(original, feed(guest).get(0).id());
        gatherings.endGathering(gathering, owner);
        assertEquals(1, feed(guest).size());
        gatherings.removeMoments(gathering, new RemoveGatheringMomentsRequest(List.of(moment)), owner);
        assertTrue(feed(guest).isEmpty());
        gatherings.dissolveGathering(gathering, owner);
        assertTrue(feed(guest).isEmpty());
    }

    @Test void summariesShowLatestContentWithoutChangingSharingTime() {
        UUID circle = newCircle(), moment = newMoment(owner);
        shareMoment(circle, moment, owner);
        var original = feed(guest).get(0);
        Moment changed = moments.selectById(moment); changed.setTitle("最新标题"); changed.setBody("😀".repeat(120));
        moments.updateById(changed);
        var current = feed(guest).get(0);
        assertEquals(original.id(), current.id()); assertEquals(original.sharedAt(), current.sharedAt());
        assertEquals("最新标题", current.moment().title()); assertEquals("😀".repeat(100), current.moment().bodyPreview());
        UUID shareId = current.shareId();
        circles.withdrawShare(circle, shareId, owner);
        assertTrue(feed(guest).isEmpty());
        shareMoment(circle, moment, owner);
        assertEquals(1, feed(guest).size()); assertNotEquals(original.id(), feed(guest).get(0).id());
    }

    @Test void albumCardsCountEachOwnerAndHandleMissingProfilesAndCovers() {
        UUID circle = newCircle(), cover = UuidCreator.getTimeOrderedEpoch();
        UUID ownAlbum = newAlbum(owner, "😀".repeat(120), cover), otherAlbum = newAlbum(guest, null, null);
        UUID ownMoment = newMoment(owner), otherMoment = newMoment(guest);
        addAlbumMoment(ownAlbum, ownMoment); addAlbumMoment(ownAlbum, otherMoment);
        addAlbumMoment(otherAlbum, otherMoment);
        when(media.findImageKeys(List.of(cover))).thenReturn(Map.of(cover, "cover-key"));
        when(media.createReadUrl("cover-key")).thenReturn("signed-cover");
        circles.shareToCircles(new CreateCircleSharesRequest(CircleShareObjectType.album, ownAlbum, List.of(circle)), owner);
        circles.shareToCircles(new CreateCircleSharesRequest(CircleShareObjectType.album, otherAlbum, List.of(circle)), guest);
        var before = feed(guest);
        var own = before.stream().filter(row -> row.album().id().equals(ownAlbum)).findFirst().orElseThrow();
        assertEquals("😀".repeat(100), own.album().descriptionPreview()); assertEquals("signed-cover", own.album().coverUrl());
        assertEquals(owner, own.album().owner().id()); assertEquals(1, own.album().momentCount());
        var other = before.stream().filter(row -> row.album().id().equals(otherAlbum)).findFirst().orElseThrow();
        assertEquals(guest, other.album().owner().id()); assertNull(other.album().coverUrl()); assertEquals(1, other.album().momentCount());
        moments.deleteById(ownMoment);
        when(users.getPublicUsers(anyCollection())).thenReturn(Map.of());
        when(media.findImageKeys(anyCollection())).thenReturn(Map.of());
        var after = feed(guest).stream().filter(row -> row.album().id().equals(ownAlbum)).findFirst().orElseThrow();
        assertEquals(own.id(), after.id()); assertEquals(own.sharedAt(), after.sharedAt());
        assertEquals(0, after.album().momentCount()); assertNull(after.actor()); assertNull(after.album().owner()); assertNull(after.album().coverUrl());
        albums.deleteById(ownAlbum);
        assertEquals(1, feed(guest).size());
    }

    @Test void actualGetRouteBindsGenericCursorAndValidatesQuery() throws Exception {
        UUID circle = newCircle(); shareMoment(circle, newMoment(owner), owner); shareMoment(circle, newMoment(owner), owner);
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var first = related.browseRelated(guest, new PageRequest<RelatedCursor>(null, 1));
        mvc.perform(get("/moments/related").requestAttr(JwtAuthFilter.CURRENT_USER_ID, guest)
                        .param("limit", "1").param("cursor.sharedAt", first.nextCursor().sharedAt().toString())
                        .param("cursor.id", first.nextCursor().id().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value("SUCCESS"))
                .andExpect(jsonPath("data.items.length()").value(1)).andExpect(jsonPath("data.hasMore").value(false));
        for (String limit : List.of("0", "101", "abc")) {
            mvc.perform(get("/moments/related").requestAttr(JwtAuthFilter.CURRENT_USER_ID, guest).param("limit", limit))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/moments/related").requestAttr(JwtAuthFilter.CURRENT_USER_ID, guest).param("cursor.id", UuidCreator.getTimeOrderedEpoch().toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/moments/related").requestAttr(JwtAuthFilter.CURRENT_USER_ID, guest)
                        .param("cursor.sharedAt", "+10000-01-01T00:00:00Z").param("cursor.id", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
    }
    @Test void fillsPagesAcrossMultipleBatchesOfStaleCandidates() {
        UUID circle = newCircle();
        for (int i = 0; i < 4; i++) shareMoment(circle, newMoment(owner), owner);
        // 最新的 35 条历史入口全部失效，首个扫描批次会全被过滤。
        seedStaleCandidates(circle, LocalDateTime.of(2027, 1, 1, 0, 0));
        var first = related.browseRelated(guest, new PageRequest<RelatedCursor>(null, 2));
        assertEquals(2, first.items().size()); assertTrue(first.hasMore());
        var second = related.browseRelated(guest, new PageRequest<>(first.nextCursor(), 2));
        assertEquals(2, second.items().size()); assertFalse(second.hasMore());
        Set<UUID> ids = new HashSet<>(first.items().stream().map(RelatedResponse::id).toList());
        assertTrue(second.items().stream().noneMatch(row -> ids.contains(row.id())));
    }

    @Test void exhaustsStaleTailBeforeReportingHasMore() {
        UUID circle = newCircle(); shareMoment(circle, newMoment(owner), owner);
        // 一条有效记录后仍有超过一批失效历史，扫描完这些历史后应返回末页。
        seedStaleCandidates(circle, LocalDateTime.of(2020, 1, 1, 0, 0));
        var page = related.browseRelated(guest, new PageRequest<RelatedCursor>(null, 1));
        assertEquals(1, page.items().size()); assertFalse(page.hasMore()); assertNull(page.nextCursor());
    }

    /** 构造仍保留在信箱、但分享引用及原内容已失效的历史入口，测试跨批扫描。 */
    private void seedStaleCandidates(UUID circle, LocalDateTime sharedAt) {
        // 每条保留不同动态 ID，来源圈子和接收者仍有效，筛选应发生在分享与内容阶段。
        for (int i = 0; i < 35; i++) {
            jdbc.update("""
                    INSERT INTO user_inbox (id, created_at, updated_at, recipient_user_id, actor_user_id,
                        source_type, source_id, object_type, object_id, shared_at, share_id)
                    VALUES (?, ?, ?, ?, ?, 'circle', ?, 'moment', ?, ?, ?)
                    """, bytes(UuidCreator.getTimeOrderedEpoch()), oldTime, oldTime, bytes(guest), bytes(owner),
                    bytes(circle), bytes(UuidCreator.getTimeOrderedEpoch()), sharedAt, bytes(UuidCreator.getTimeOrderedEpoch()));
        }
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

    /** 发布一条圈子分享，使用真实同步信箱投递。 */
    private void shareMoment(UUID circle, UUID moment, UUID actor) {
        circles.shareToCircles(new CreateCircleSharesRequest(CircleShareObjectType.moment, moment, List.of(circle)), actor);
    }

    /** 返回当前用户首屏，足以覆盖本测试的全部卡片。 */
    private List<RelatedResponse> feed(UUID user) {
        return related.browseRelated(user, new PageRequest<>(null, 100)).items();
    }

    /** 建立时光及封面引用，时光成员由测试各自控制。 */
    private UUID newAlbum(UUID user, String description, UUID cover) {
        Album album = new Album(); album.setId(UuidCreator.getTimeOrderedEpoch());
        album.setOwnerUserId(user); album.setName("测试时光"); album.setDescription(description);
        album.setCoverFileId(cover); album.setOrganizeMode(AlbumOrganizeMode.manual);
        album.setCreatedAt(oldTime); album.setUpdatedAt(oldTime); albums.insert(album);
        return album.getId();
    }

    /** 添加时光关联，用真实查询检查作者范围与软删除。 */
    private void addAlbumMoment(UUID album, UUID moment) {
        AlbumMoment link = new AlbumMoment(); link.setId(UuidCreator.getTimeOrderedEpoch());
        link.setAlbumId(album); link.setMomentId(moment); link.setCreatedAt(oldTime); link.setUpdatedAt(oldTime);
        albumMoments.insert(link);
    }

    /** 原生 SQL 的 UUID 参数沿用项目的二进制顺序。 */
    private byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
