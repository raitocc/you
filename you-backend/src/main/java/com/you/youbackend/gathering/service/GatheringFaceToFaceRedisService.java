package com.you.youbackend.gathering.service;

import com.you.youbackend.gathering.model.FaceToFaceMatch;
import com.you.youbackend.gathering.model.FaceToFaceRules;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 面对面位置存取，只保存 GEO 成员：同行 ID 对应发起经纬度。
 * <p>Key 格式为“前缀 + 短码 + : + 十分钟段起点的 Unix 秒数”，时间段依据同行创建时间确定。
 * 例如 14:00～14:10 创建的同行放在 14:00 段，该 Key 固定在 14:20 到期，持续写入也保持这个时间。
 * <p>预览只查询当前段和上一段，覆盖最近 10 分钟创建的同行，再由业务层查询 MySQL 判断实际有效期。
 * 过期位置最多额外保留一个窗口，随后随整个 Key 回收；热门短码会进入新时间段。
 */
@Service
@RequiredArgsConstructor
public class GatheringFaceToFaceRedisService {
    private final StringRedisTemplate redisTemplate;

    /**
     * 保存首次发起位置，并设置所属时间段的固定到期时间。
     * @param gatheringId 已由数据库成功登记首次短码的同行 ID
     * @param faceCode 数据库中登记的四位短码
     * @param createdAt 同行创建时间，用于确定位置所属时间段
     * @param location 发起位置，Point 的 x 为经度、y 为纬度
     */
    public void saveLocation(UUID gatheringId, String faceCode, Instant createdAt, Point location) {
        // 1. 位置始终归入同行创建时间所属的段，避免开启时间跨段后遗漏候选。
        Instant start = periodStart(createdAt);
        String key = geoKey(faceCode, start);
        redisTemplate.opsForGeo().add(key, location, gatheringId.toString());
        // 2. 固定在段起点后 20 分钟清理，后续写入保持相同到期时间。
        redisTemplate.expireAt(key, start.plus(FaceToFaceRules.WINDOW.multipliedBy(2)));
    }

    /**
     * 查询当前与上一时间段内的附近同行，合并后按距离升序返回。
     * @param faceCode 已校验的四位短码
     * @param latitude 查询者当前纬度
     * @param longitude 查询者当前经度
     * @param now 本次业务查询统一使用的时间，与数据库有效期筛选保持一致
     * @return 附近同行 ID 及米数，实际邀请有效性由业务层确认
     */
    public List<FaceToFaceMatch> findNearby(String faceCode, double latitude, double longitude, Instant now) {
        // 1. 有效同行的创建时间只可能落在当前段或上一段，分别查询两个 GEO Key。
        Instant start = periodStart(now);
        List<FaceToFaceMatch> matches = new ArrayList<>(
                queryNearby(geoKey(faceCode, start), latitude, longitude));
        matches.addAll(queryNearby(geoKey(faceCode, start.minus(FaceToFaceRules.WINDOW)), latitude, longitude));
        // 2. 两个时间段的结果统一排序，供业务层筛选后保持相同顺序。
        matches.sort(Comparator.comparingDouble(FaceToFaceMatch::distanceMeters));
        return matches;
    }

    /**
     * 查询一个时间段的 GEO 候选。
     * @param key 完整 GEO Key
     * @param latitude 查询纬度
     * @param longitude 查询经度
     * @return 100 米内的同行与距离，Key 缺失时为空
     */
    private List<FaceToFaceMatch> queryNearby(String key, double latitude, double longitude) {
        // GEO 坐标使用经度在前、纬度在后的顺序，距离统一返回米数。
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(
                key, GeoReference.fromCoordinate(longitude, latitude),
                new Distance(FaceToFaceRules.RADIUS_METERS, RedisGeoCommands.DistanceUnit.METERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance());
        List<FaceToFaceMatch> matches = new ArrayList<>();
        if (results != null) {
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results.getContent()) {
                matches.add(new FaceToFaceMatch(UUID.fromString(result.getContent().getName()),
                        result.getDistance().getValue()));
            }
        }
        return matches;
    }

    /**
     * 计算指定时间所属十分钟段的起点。
     * @param time 同行创建时间或本次查询时间
     * @return 以 Unix 秒数划分的段起点，计算与机器时区无关
     */
    private Instant periodStart(Instant time) {
        // 每 600 秒为一段，向下取整后还原为时间点。
        long seconds = FaceToFaceRules.WINDOW.toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(time.getEpochSecond(), seconds) * seconds);
    }

    /**
     * 构造短码在指定时间段的 GEO Key。
     * @param faceCode 四位短码
     * @param start 已计算的时间段起点
     * @return 带业务前缀的完整 Key
     */
    private String geoKey(String faceCode, Instant start) {
        // 时间段属于 Key 的一部分，旧段可独立到期。
        return FaceToFaceRules.GEO_KEY_PREFIX + faceCode + ":" + start.getEpochSecond();
    }
}