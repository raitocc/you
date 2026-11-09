package com.you.youbackend.gathering;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.gathering.entity.GatheringMember;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.FaceToFaceRules;
import com.you.youbackend.gathering.request.CreateGatheringRequest;
import com.you.youbackend.gathering.request.FaceToFacePreviewRequest;
import com.you.youbackend.gathering.request.JoinFaceToFaceRequest;
import com.you.youbackend.gathering.request.OpenFaceToFaceRequest;
import com.you.youbackend.gathering.response.GatheringResponse;
import com.you.youbackend.gathering.service.GatheringFaceToFaceService;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.user.mapper.UserMapper;
import com.you.youbackend.user.model.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 使用本机 Redis 与 MySQL 验证面对面位置匹配、入口过期与成员关系写入。 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Transactional
class GatheringFaceToFaceIntegrationTest {
    @Autowired private GatheringFaceToFaceService faceService;
    @Autowired private GatheringService gatheringService;
    @Autowired private GatheringMapper gatherings;
    @Autowired private GatheringMemberMapper members;
    @Autowired private UserMapper users;
    @Autowired private StringRedisTemplate redis;
    @MockitoBean private MediaService media;
    private final Map<UUID, String> entries = new HashMap<>();
    private final String faceCode = String.format("%04d", ThreadLocalRandom.current().nextInt(10000));

    @AfterEach
    void cleanOwnRedisEntries() {
        // 数据库由测试事务回滚；Redis 只移除本测试创建的 ID，保留同码的其他业务入口。
        entries.forEach((id, code) -> {
            redis.delete(FaceToFaceRules.ENTRY_KEY_PREFIX + id);
            redis.opsForGeo().remove(FaceToFaceRules.GEO_KEY_PREFIX + code, id.toString());
        });
    }

    @Test
    void listsNearbySameCodeTargetsAndJoinsOnlySelectedOne() {
        AppUser firstCreator = saveUser(), secondCreator = saveUser(), guest = saveUser();
        var first = open(firstCreator, 30, 120);
        var second = open(secondCreator, 30.00045, 120);
        var candidates = faceService.preview(new FaceToFacePreviewRequest(faceCode, 30d, 120d), guest.getId());
        var ownCandidates = candidates.stream().filter(item -> entries.containsKey(item.id())).toList();
        assertEquals(2, ownCandidates.size());
        assertEquals(first.id(), ownCandidates.get(0).id());
        assertEquals(second.id(), ownCandidates.get(1).id());
        assertTrue(ownCandidates.get(0).distanceMeters() <= ownCandidates.get(1).distanceMeters());
        assertFalse(ownCandidates.get(0).isMember());
        assertEquals(firstCreator.getId(), ownCandidates.get(0).creator().id());

        var request = new JoinFaceToFaceRequest(second.id(), faceCode, 30d, 120d);
        var joined = faceService.join(request, guest.getId());
        assertEquals(second.id(), joined.id());
        assertEquals(2, joined.memberCount());
        assertFalse(members.isActiveMember(first.id(), guest.getId()));
        GatheringMember original = findMember(second.id(), guest.getId());
        faceService.join(request, guest.getId());
        assertEquals(original.getUpdatedAt(), findMember(second.id(), guest.getId()).getUpdatedAt());

        // 同一路径也能恢复退出记录，保留原关系 ID 与创建时间。
        members.deleteById(original.getId());
        faceService.join(request, guest.getId());
        var restored = findMember(second.id(), guest.getId());
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getCreatedAt(), restored.getCreatedAt());
        assertNull(restored.getDeletedAt());
    }

    @Test
    void sameCodeCanBeUsedElsewhereAndRadiusIs100Meters() {
        AppUser creator = saveUser(), guest = saveUser();
        var nearby = open(creator, 30, 120);
        var distant = open(creator, 31, 120);
        var request = new FaceToFacePreviewRequest(faceCode, 30.00089, 120d);
        var candidates = faceService.preview(request, guest.getId());
        assertTrue(candidates.stream().anyMatch(item -> item.id().equals(nearby.id())));
        assertFalse(candidates.stream().anyMatch(item -> item.id().equals(distant.id())));
        var outside = faceService.preview(new FaceToFacePreviewRequest(faceCode, 30.00091, 120d), guest.getId());
        assertFalse(outside.stream().anyMatch(item -> item.id().equals(nearby.id())));
        assertThrows(BusinessException.class, () -> faceService.join(
                new JoinFaceToFaceRequest(nearby.id(), faceCode, 30.00091, 120d), guest.getId()));
    }

    @Test
    void repeatedOpeningKeepsOriginalCodeLocationAndDeadline() {
        AppUser creator = saveUser();
        var gathering = gatheringService.createGathering(new CreateGatheringRequest("固定窗口测试", null), creator.getId());
        var saved = gatherings.selectById(gathering.id());
        saved.setCreatedAt(saved.getCreatedAt().minusMinutes(3));
        gatherings.updateById(saved);
        entries.put(gathering.id(), faceCode);
        var first = faceService.open(gathering.id(), new OpenFaceToFaceRequest(faceCode, 30d, 120d), creator.getId());
        var repeated = faceService.open(gathering.id(), new OpenFaceToFaceRequest("9999", 31d, 121d), creator.getId());
        assertEquals(first, repeated);
        assertEquals(saved.getCreatedAt().toInstant(ZoneOffset.UTC).plus(FaceToFaceRules.WINDOW), first.expiresAt());
        long ttl = redis.getExpire(FaceToFaceRules.ENTRY_KEY_PREFIX + gathering.id(), TimeUnit.MILLISECONDS);
        assertTrue(ttl > 0 && ttl <= Duration.ofMinutes(7).toMillis());
        var candidates = faceService.preview(new FaceToFacePreviewRequest(faceCode, 30d, 120d), creator.getId());
        assertTrue(candidates.stream().anyMatch(item -> item.id().equals(gathering.id())));
    }

    @Test
    void expiredMetadataLeavesNoCandidateAndCannotBeUsedToJoin() {
        AppUser creator = saveUser(), guest = saveUser();
        var gathering = open(creator, 30, 120);
        // 直接使元数据 TTL 到期，GEO 中的旧成员仍存在，查询应忽略该残留。
        redis.expire(FaceToFaceRules.ENTRY_KEY_PREFIX + gathering.id(), Duration.ZERO);
        var candidates = faceService.preview(new FaceToFacePreviewRequest(faceCode, 30d, 120d), guest.getId());
        assertFalse(candidates.stream().anyMatch(item -> item.id().equals(gathering.id())));
        assertThrows(BusinessException.class, () -> faceService.join(
                new JoinFaceToFaceRequest(gathering.id(), faceCode, 30d, 120d), guest.getId()));
        assertFalse(members.isActiveMember(gathering.id(), guest.getId()));
    }

    @Test
    void databaseInvitationChangesTakeEffectEvenWhenRedisEntryRemains() {
        AppUser creator = saveUser(), guest = saveUser();
        var gathering = open(creator, 30, 120);
        var saved = gatherings.selectById(gathering.id());
        saved.setInviteEnabled(false);
        gatherings.updateById(saved);
        assertFalse(faceService.preview(new FaceToFacePreviewRequest(faceCode, 30d, 120d), guest.getId())
                .stream().anyMatch(item -> item.id().equals(gathering.id())));
        assertThrows(BusinessException.class, () -> faceService.join(
                new JoinFaceToFaceRequest(gathering.id(), faceCode, 30d, 120d), guest.getId()));
    }

    @Test
    void oldGatheringCannotOpenEntry() {
        AppUser creator = saveUser();
        var gathering = gatheringService.createGathering(new CreateGatheringRequest("旧同行测试", null), creator.getId());
        var saved = gatherings.selectById(gathering.id());
        saved.setCreatedAt(saved.getCreatedAt().minusMinutes(11));
        gatherings.updateById(saved);
        entries.put(gathering.id(), faceCode);
        var exception = assertThrows(BusinessException.class, () -> faceService.open(
                gathering.id(), new OpenFaceToFaceRequest(faceCode, 30d, 120d), creator.getId()));
        assertEquals("FACE_TO_FACE_WINDOW_EXPIRED", exception.getCode());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(FaceToFaceRules.ENTRY_KEY_PREFIX + gathering.id())));
    }

    /** 创建同行并开启独立入口，登记当前测试拥有的 Redis 成员。 */
    private GatheringResponse open(AppUser creator, double latitude, double longitude) {
        var gathering = gatheringService.createGathering(new CreateGatheringRequest("面对面集成测试", null), creator.getId());
        entries.put(gathering.id(), faceCode);
        faceService.open(gathering.id(), new OpenFaceToFaceRequest(faceCode, latitude, longitude), creator.getId());
        return gathering;
    }

    /** 创建随机测试用户，避免依赖已有业务账号。 */
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

    /** 读取有效成员，验证重复加入及恢复时的身份与审计字段。 */
    private GatheringMember findMember(UUID gatheringId, UUID userId) {
        return members.selectOne(Wrappers.<GatheringMember>lambdaQuery()
                .eq(GatheringMember::getGatheringId, gatheringId).eq(GatheringMember::getUserId, userId));
    }
}