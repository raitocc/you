package com.you.youbackend.gathering;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.gathering.entity.GatheringMember;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.FaceToFaceMatch;
import com.you.youbackend.gathering.model.FaceToFaceRules;
import com.you.youbackend.gathering.request.CreateGatheringRequest;
import com.you.youbackend.gathering.request.FaceToFacePreviewRequest;
import com.you.youbackend.gathering.request.JoinFaceToFaceRequest;
import com.you.youbackend.gathering.request.OpenFaceToFaceRequest;
import com.you.youbackend.gathering.response.GatheringResponse;
import com.you.youbackend.gathering.service.GatheringFaceToFaceRedisService;
import com.you.youbackend.gathering.service.GatheringFaceToFaceService;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.user.mapper.UserMapper;
import com.you.youbackend.user.model.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** 使用隔离 MySQL 测试库与本机 Redis 验证短码、GEO 时间段、实际事务与成员维护。 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Transactional
class GatheringFaceToFaceIntegrationTest {
    @Autowired private GatheringFaceToFaceService faceService;
    @Autowired private GatheringService gatheringService;
    @Autowired private GatheringMapper gatherings;
    @Autowired private GatheringMemberMapper members;
    @Autowired private UserMapper users;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private GatheringFaceToFaceRedisService locations;
    @MockitoBean private MediaService media;
    private final Map<UUID, String> geoKeys = new HashMap<>();
    private final String faceCode = String.format("%04d", ThreadLocalRandom.current().nextInt(10000));

    @AfterEach
    void cleanOwnRedisMembers() {
        // MySQL 测试事务回滚；Redis 仅清理本测试的随机 ID，保留同码的其他成员。
        geoKeys.forEach((id, key) -> redis.opsForGeo().remove(key, id.toString()));
    }

    @Test
    void listsNearbyTargetsAndJoinsSelectedOneWithoutLocationOrRedisData() {
        AppUser firstCreator = saveUser(), secondCreator = saveUser(), guest = saveUser();
        var first = open(firstCreator, 30, 120);
        var second = open(secondCreator, 30.00045, 120);
        var candidates = faceService.preview(new FaceToFacePreviewRequest(faceCode, 30d, 120d), guest.getId());
        var own = candidates.stream().filter(item -> geoKeys.containsKey(item.gathering().id())).toList();
        assertEquals(List.of(first.id(), second.id()), own.stream().map(item -> item.gathering().id()).toList());
        assertTrue(own.get(0).distanceMeters() <= own.get(1).distanceMeters());
        assertFalse(own.get(0).isMember());
        assertEquals(firstCreator.getId(), own.get(0).gathering().creator().id());

        // 删除目标的 GEO 成员，确认加入仍然只依据数据库短码及有效期。
        redis.opsForGeo().remove(geoKeys.get(second.id()), second.id().toString());
        var request = new JoinFaceToFaceRequest(second.id(), faceCode);
        var joined = gatheringService.joinFromFaceToFace(request, guest.getId());
        assertEquals(second.id(), joined.id());
        assertEquals(2, joined.memberCount());
        assertFalse(members.isActiveMember(first.id(), guest.getId()));
        GatheringMember original = findMember(second.id(), guest.getId());
        gatheringService.joinFromFaceToFace(request, guest.getId());
        assertEquals(original.getUpdatedAt(), findMember(second.id(), guest.getId()).getUpdatedAt());

        members.deleteById(original.getId());
        gatheringService.joinFromFaceToFace(request, guest.getId());
        GatheringMember restored = findMember(second.id(), guest.getId());
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getCreatedAt(), restored.getCreatedAt());
        assertNull(restored.getDeletedAt());
    }

    @Test
    void limitsPreviewTo100MetersWhileSameCodeWorksAtOtherLocations() {
        AppUser creator = saveUser(), guest = saveUser();
        var nearby = open(creator, 30, 120);
        var distant = open(creator, 31, 120);
        var inside = faceService.preview(new FaceToFacePreviewRequest(faceCode, 30.00089, 120d), guest.getId());
        assertTrue(inside.stream().anyMatch(item -> item.gathering().id().equals(nearby.id())));
        assertFalse(inside.stream().anyMatch(item -> item.gathering().id().equals(distant.id())));
        var outside = faceService.preview(new FaceToFacePreviewRequest(faceCode, 30.00091, 120d), guest.getId());
        assertFalse(outside.stream().anyMatch(item -> item.gathering().id().equals(nearby.id())));
        assertEquals(nearby.id(), gatheringService.joinFromFaceToFace(
                new JoinFaceToFaceRequest(nearby.id(), faceCode), guest.getId()).id());
    }

    @Test
    void keepsPermanentFirstCodeAndRejectsAllSubsequentOpenings() {
        AppUser creator = saveUser();
        var gathering = open(creator, 30, 120);
        var saved = gatherings.selectById(gathering.id());
        assertEquals(faceCode, saved.getFaceCode());
        assertEquals(0, gatherings.setFaceCodeIfAbsent(gathering.id(), "9999", LocalDateTime.now(ZoneOffset.UTC)));

        var error = assertThrows(BusinessException.class, () -> faceService.open(
                gathering.id(), new OpenFaceToFaceRequest("9999", 31d, 121d), creator.getId()));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(faceCode, gatherings.selectById(gathering.id()).getFaceCode());
        Point original = redis.opsForGeo().position(geoKeys.get(gathering.id()), gathering.id().toString()).get(0);
        assertEquals(120, original.getX(), 0.00001);
        assertEquals(30, original.getY(), 0.00001);

        // 业务时间到期后，永久短码仍作为已经开启过的记录保留。
        saved.setCreatedAt(saved.getCreatedAt().minusMinutes(11));
        gatherings.updateById(saved);
        assertEquals("FACE_TO_FACE_ALREADY_OPENED", assertThrows(BusinessException.class, () -> faceService.open(
                gathering.id(), new OpenFaceToFaceRequest(faceCode, 30d, 120d), creator.getId())).getCode());
        assertEquals(faceCode, gatherings.selectById(gathering.id()).getFaceCode());
    }

    @Test
    void filtersExpiredLocationsAndDatabaseCodeMismatches() {
        AppUser creator = saveUser(), guest = saveUser();
        var expired = open(creator, 30, 120);
        var mismatch = open(creator, 30.0001, 120);
        var saved = gatherings.selectById(expired.id());
        saved.setCreatedAt(saved.getCreatedAt().minusMinutes(11));
        gatherings.updateById(saved);
        var another = gatherings.selectById(mismatch.id());
        another.setFaceCode(faceCode.equals("9999") ? "0000" : "9999");
        gatherings.updateById(another);

        var candidates = faceService.preview(new FaceToFacePreviewRequest(faceCode, 30d, 120d), guest.getId());
        assertFalse(candidates.stream().anyMatch(item -> item.gathering().id().equals(expired.id()) || item.gathering().id().equals(mismatch.id())));
        for (UUID id : List.of(expired.id(), mismatch.id())) {
            assertThrows(BusinessException.class, () -> gatheringService.joinFromFaceToFace(
                    new JoinFaceToFaceRequest(id, faceCode), guest.getId()));
            assertFalse(members.isActiveMember(id, guest.getId()));
        }
    }

    @Test
    void closedAndDissolvedGatheringsDisappearFromPreviewAndRejectJoining() {
        AppUser creator = saveUser(), guest = saveUser();
        var closed = open(creator, 30, 120);
        var dissolved = open(creator, 30.0001, 120);
        var saved = gatherings.selectById(closed.id());
        saved.setInviteEnabled(false);
        gatherings.updateById(saved);
        gatherings.deleteById(dissolved.id());

        var candidates = faceService.preview(new FaceToFacePreviewRequest(faceCode, 30d, 120d), guest.getId());
        assertFalse(candidates.stream().anyMatch(item -> item.gathering().id().equals(closed.id()) || item.gathering().id().equals(dissolved.id())));
        for (UUID id : List.of(closed.id(), dissolved.id())) {
            assertThrows(BusinessException.class, () -> gatheringService.joinFromFaceToFace(
                    new JoinFaceToFaceRequest(id, faceCode), guest.getId()));
        }
    }

    @Test
    void oldGatheringCannotOpenAndLeavesFaceCodeEmpty() {
        AppUser creator = saveUser();
        var gathering = gatheringService.createGathering(new CreateGatheringRequest("旧同行测试", null), creator.getId());
        var saved = gatherings.selectById(gathering.id());
        saved.setCreatedAt(saved.getCreatedAt().minusMinutes(11));
        gatherings.updateById(saved);
        assertEquals("FACE_TO_FACE_WINDOW_EXPIRED", assertThrows(BusinessException.class, () -> faceService.open(
                gathering.id(), new OpenFaceToFaceRequest(faceCode, 30d, 120d), creator.getId())).getCode());
        assertNull(gatherings.selectById(gathering.id()).getFaceCode());
    }

    @Test
    void findsPreviousAndCurrentPeriodsAndMergesDistanceOrder() {
        // 用确定的查询时间验证跨段，不依赖测试刚好运行在整十分钟边界。
        Instant boundary = periodStart(Instant.now()).plus(FaceToFaceRules.WINDOW);
        UUID previous = saveLocation(boundary.minusSeconds(1), 30, 120);
        UUID current = saveLocation(boundary.plusSeconds(1), 30.00045, 120);
        UUID older = saveLocation(boundary.minusSeconds(601), 30, 120);
        var matches = locations.findNearby(faceCode, 30, 120, boundary.plusSeconds(2)).stream()
                .filter(match -> geoKeys.containsKey(match.gatheringId())).toList();
        assertEquals(List.of(previous, current), matches.stream().map(FaceToFaceMatch::gatheringId).toList());
        assertFalse(matches.stream().anyMatch(match -> match.gatheringId().equals(older)));
        assertTrue(matches.get(0).distanceMeters() < matches.get(1).distanceMeters());
    }

    @Test
    void repeatedWritesKeepFixedExpirationAndNextPeriodUsesAnotherKey() {
        Instant start = periodStart(Instant.now());
        UUID first = saveLocation(start.plusSeconds(1), 30, 120);
        String key = geoKeys.get(first);
        long before = redis.getExpire(key, TimeUnit.MILLISECONDS);
        UUID second = saveLocation(start.plusSeconds(2), 30.0001, 120);
        long after = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertEquals(key, geoKeys.get(second));
        assertTrue(after <= before);
        long expected = start.plus(FaceToFaceRules.WINDOW.multipliedBy(2)).toEpochMilli() - Instant.now().toEpochMilli();
        assertEquals(expected, after, 1000);
        UUID next = saveLocation(start.plus(FaceToFaceRules.WINDOW), 30, 120);
        assertNotEquals(key, geoKeys.get(next));
        assertTrue(redis.getExpire(geoKeys.get(next), TimeUnit.MILLISECONDS) > after);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollsBackPermanentCodeWhenRedisWriteFails() {
        // 使用真实 Service 事务，外层无事务，从独立查询观察回滚结果。
        AppUser creator = saveUser();
        var gathering = gatheringService.createGathering(new CreateGatheringRequest("位置失败回滚测试", null), creator.getId());
        try {
            doThrow(new IllegalStateException("模拟 Redis 失败")).when(locations).saveLocation(any(), any(), any(), any());
            assertThrows(IllegalStateException.class, () -> faceService.open(
                    gathering.id(), new OpenFaceToFaceRequest(faceCode, 30d, 120d), creator.getId()));
            assertNull(gatherings.selectById(gathering.id()).getFaceCode());
        } finally {
            // 仅清理本测试提交的随机记录，其他测试由各自事务回滚。
            jdbc.update("DELETE FROM gathering_member WHERE gathering_id = ?", bytes(gathering.id()));
            jdbc.update("DELETE FROM gathering WHERE id = ?", bytes(gathering.id()));
            jdbc.update("DELETE FROM app_user WHERE id = ?", bytes(creator.getId()));
        }
    }

    /** 创建同行、登记首次短码，并记录本测试需要清理的 GEO 成员。 */
    private GatheringResponse open(AppUser creator, double latitude, double longitude) {
        var gathering = gatheringService.createGathering(new CreateGatheringRequest("面对面集成测试", null), creator.getId());
        geoKeys.put(gathering.id(), geoKey(faceCode, gathering.createdAt()));
        var response = faceService.open(gathering.id(), new OpenFaceToFaceRequest(faceCode, latitude, longitude), creator.getId());
        assertEquals(gathering.createdAt().plus(FaceToFaceRules.WINDOW), response.expiresAt());
        return gathering;
    }

    /** 单独准备 GEO 位置，使用指定时间验证时间段查询和清理边界。 */
    private UUID saveLocation(Instant createdAt, double latitude, double longitude) {
        UUID id = UuidCreator.getTimeOrderedEpoch();
        geoKeys.put(id, geoKey(faceCode, createdAt));
        locations.saveLocation(id, faceCode, createdAt, new Point(longitude, latitude));
        return id;
    }

    /** 计算预期 Key，供真实 Redis 的 TTL 断言和定向清理使用。 */
    private String geoKey(String code, Instant createdAt) {
        return FaceToFaceRules.GEO_KEY_PREFIX + code + ":" + periodStart(createdAt).getEpochSecond();
    }

    /** 按业务窗口计算整段起点，用于准备固定边界场景。 */
    private Instant periodStart(Instant time) {
        long seconds = FaceToFaceRules.WINDOW.toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(time.getEpochSecond(), seconds) * seconds);
    }

    /** 创建随机用户，测试不依赖现有业务账号。 */
    private AppUser saveUser() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        AppUser user = new AppUser();
        user.setId(UuidCreator.getTimeOrderedEpoch());
        user.setUsername("face_test_" + user.getId());
        user.setPasswordHash("test-only");
        user.setNickname("面对面测试用户");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        users.insert(user);
        return user;
    }

    /** 读取有效成员，比较重复加入及恢复前后的身份和时间。 */
    private GatheringMember findMember(UUID gatheringId, UUID userId) {
        return members.selectOne(Wrappers.<GatheringMember>lambdaQuery()
                .eq(GatheringMember::getGatheringId, gatheringId).eq(GatheringMember::getUserId, userId));
    }

    /** 将测试清理参数编码成数据库 UUID 字节格式。 */
    private byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}