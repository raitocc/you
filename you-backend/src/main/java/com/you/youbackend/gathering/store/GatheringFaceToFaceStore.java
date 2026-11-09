package com.you.youbackend.gathering.store;

import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.gathering.model.FaceToFaceEntry;
import com.you.youbackend.gathering.model.FaceToFaceMatch;
import com.you.youbackend.gathering.model.FaceToFaceRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 面对面临时入口存取与 GEO 距离查询，入口按同行独立过期。 */
@Log4j2
@Component
@RequiredArgsConstructor
public class GatheringFaceToFaceStore {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 首次保存入口，重复开启复用原短码、位置和截止时间，并补齐位置索引。
     * @param candidate 已通过创建者权限和窗口校验的候选入口
     * @return 实际生效的入口
     */
    public FaceToFaceEntry open(FaceToFaceEntry candidate) {
        // 1. 保存首次入口，重复请求读取原入口。
        FaceToFaceEntry entry = saveOrLoadEntry(candidate);
        // 2. 为实际生效的短码和位置补齐 GEO 索引。
        indexEntry(entry);
        return entry;
    }

    /**
     * 按同码、100 米半径查找未到期入口，结果按距离升序。
     * @param faceCode 已校验的四位数字短码
     * @param latitude 查询者当前纬度
     * @param longitude 查询者当前经度
     * @return 候选及米数，零匹配返回空列表；同行有效性由业务层批量检查
     */
    public List<FaceToFaceMatch> findNearby(String faceCode, double latitude, double longitude) {
        // 1. 按距离升序查找附近同行，再批量读取入口元数据。
        Map<UUID, Double> distances = searchNearby(faceCode, latitude, longitude);
        Map<UUID, FaceToFaceEntry> entries = loadEntries(distances.keySet());
        // 2. 过滤过期入口和已变更短码的索引，保持距离顺序。
        return matchActiveEntries(faceCode, distances, entries);
    }

    /**
     * 以首次写入为准保存入口，已存在时读取原值。
     * @param candidate 已通过业务层校验的候选入口
     * @return 尚未过期的实际入口
     */
    private FaceToFaceEntry saveOrLoadEntry(FaceToFaceEntry candidate) {
        // SET NX 仅在首次写入时设置剩余有效期，重复开启保持原入口及 TTL。
        Duration remaining = remainingLifetime(candidate);
        String key = FaceToFaceRules.ENTRY_KEY_PREFIX + candidate.gatheringId();
        boolean created = Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(key, objectMapper.writeValueAsString(candidate), remaining));
        FaceToFaceEntry entry = created ? candidate : loadEntry(key);
        if (entry == null || !entry.expiresAt().isAfter(Instant.now())) {
            throw BusinessException.badRequest("FACE_TO_FACE_WINDOW_EXPIRED", "面对面加入窗口已结束");
        }
        if (created) {
            log.info("已保存面对面入口，gatheringId={}，expiresAt={}", entry.gatheringId(), entry.expiresAt());
        } else {
            log.debug("复用面对面入口，gatheringId={}", entry.gatheringId());
        }
        return entry;
    }

    /**
     * 根据固定截止时间计算 Redis 剩余有效期。
     * @param entry 待保存入口
     * @return 正数有效期，窗口已结束时抛出业务异常
     */
    private Duration remainingLifetime(FaceToFaceEntry entry) {
        // 剩余时间依据原截止时间计算，重复开启保持同一个窗口。
        Duration remaining = Duration.between(Instant.now(), entry.expiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            throw BusinessException.badRequest("FACE_TO_FACE_WINDOW_EXPIRED", "面对面加入窗口已结束");
        }
        return remaining;
    }

    /**
     * 读取并还原一个入口。
     * @param key 完整入口键
     * @return 原入口，键已过期时返回 null
     */
    private FaceToFaceEntry loadEntry(String key) {
        // 重复开启只读取首次保存的短码、位置和截止时间。
        String saved = redisTemplate.opsForValue().get(key);
        return saved == null ? null : objectMapper.readValue(saved, FaceToFaceEntry.class);
    }

    /**
     * 保存入口位置及索引存活期；索引保留时间覆盖所有仍有效入口。
     * @param entry 实际生效的入口，包含首次发起位置
     */
    private void indexEntry(FaceToFaceEntry entry) {
        // GEO 成员以同行 ID 区分，重复开启补齐同一个成员，独立入口 TTL 保持原样。
        String key = FaceToFaceRules.GEO_KEY_PREFIX + entry.faceCode();
        redisTemplate.opsForGeo().add(key, new Point(entry.longitude(), entry.latitude()), entry.gatheringId().toString());
        redisTemplate.expire(key, FaceToFaceRules.WINDOW);
    }

    /**
     * 查找同码范围内的同行及距离。
     * @param faceCode 已校验的短码
     * @param latitude 查询纬度
     * @param longitude 查询经度
     * @return 按距离升序插入的同行 ID 与米数
     */
    private Map<UUID, Double> searchNearby(String faceCode, double latitude, double longitude) {
        // Redis GEO 坐标为经度在前、纬度在后，查询直接返回升序米数。
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(
                FaceToFaceRules.GEO_KEY_PREFIX + faceCode,
                GeoReference.fromCoordinate(longitude, latitude),
                new Distance(FaceToFaceRules.RADIUS_METERS, RedisGeoCommands.DistanceUnit.METERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending());
        Map<UUID, Double> distances = new LinkedHashMap<>();
        if (results != null) {
            for ( GeoResult<RedisGeoCommands.GeoLocation<String>> result : results.getContent()) {
                distances.put(
                        UUID.fromString(result.getContent().getName()),
                        result.getDistance().getValue());
            }
        }
        return distances;
    }

    /**
     * 批量读取入口，以同行 ID 关联元数据。
     * @param gatheringIds GEO 查询得到的同行 ID
     * @return 仍存于 Redis 的入口，已过期键从结果中跳过
     */
    private Map<UUID, FaceToFaceEntry> loadEntries(Set<UUID> gatheringIds) {
        // 空候选直接返回，其余入口通过一次 MGET 读取。
        if (gatheringIds.isEmpty()) {
            return Map.of();
        }
        List<String> keys = gatheringIds.stream().map(id -> FaceToFaceRules.ENTRY_KEY_PREFIX + id).toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        return values.stream().filter(Objects::nonNull)
                .map(value -> objectMapper.readValue(value, FaceToFaceEntry.class))
                .collect(Collectors.toMap(FaceToFaceEntry::gatheringId, Function.identity()));
    }

    /**
     * 根据入口有效性筛选匹配结果。
     * @param faceCode 本次查询短码
     * @param distances 按距离升序插入的同行 ID 与米数
     * @param entries 批量读取的入口，以同行 ID 索引
     * @return 按距离升序的有效匹配
     */
    private List<FaceToFaceMatch> matchActiveEntries(String faceCode, Map<UUID, Double> distances,
                                                    Map<UUID, FaceToFaceEntry> entries) {
        // GEO 索引只有整体 TTL，逐项以独立元数据和截止时间判断入口是否有效。
        List<FaceToFaceMatch> matches = new ArrayList<>();
        Instant now = Instant.now();
        for (var distance : distances.entrySet()) {
            FaceToFaceEntry entry = entries.get(distance.getKey());
            if (entry != null && faceCode.equals(entry.faceCode()) && entry.expiresAt().isAfter(now)) {
                matches.add(new FaceToFaceMatch(entry, distance.getValue()));
            }
        }
        return matches;
    }
}
