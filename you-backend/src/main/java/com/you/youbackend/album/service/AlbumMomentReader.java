package com.you.youbackend.album.service;

import com.you.youbackend.album.entity.AlbumMoment;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.album.model.AlbumMomentCount;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 根据有效关联和瞬间状态读取时光线并统计数量。 */
@Component
@RequiredArgsConstructor
public class AlbumMomentReader {
    /** 限制单次 IN 查询的参数数量。 */
    private static final int MOMENT_BATCH_SIZE = 500;
    /** UUID 字符串正序与数据库 BINARY(16) 正序一致。 */
    private static final Comparator<Moment> TIMELINE_ORDER = Comparator.comparing(Moment::getMemoryTime)
            .thenComparing(Moment::getCreatedAt).thenComparing(moment -> moment.getId().toString());

    private final AlbumMomentMapper associations;
    private final MomentMapper moments;

    /** 统计指定时光中关联有效且由本人发布的瞬间。 */
    public long countValidMoments(UUID albumId, UUID userId) {
        // 复用批量统计，空时光没有分组结果。
        return countValidMomentsByAlbum(List.of(albumId), userId).stream()
                .mapToLong(AlbumMomentCount::momentCount).sum();
    }

    /** 批量统计本页时光中的有效本人瞬间。 */
    public List<AlbumMomentCount> countValidMomentsByAlbum(List<UUID> albumIds, UUID userId) {
        // 批量读取关联，分批过滤已删除或他人发布的瞬间，再按时光归组。
        var links = associations.selectActiveLinks(albumIds);
        var ids = links.stream().map(AlbumMoment::getMomentId).distinct().toList();
        Set<UUID> validIds = new HashSet<>();
        for (int start = 0; start < ids.size(); start += MOMENT_BATCH_SIZE) {
            var batch = ids.subList(start, Math.min(start + MOMENT_BATCH_SIZE, ids.size()));
            validIds.addAll(moments.selectOwnedActiveIds(batch, userId));
        }
        return links.stream().filter(link -> validIds.contains(link.getMomentId()))
                .collect(Collectors.groupingBy(AlbumMoment::getAlbumId, Collectors.counting()))
                .entrySet().stream().map(entry -> new AlbumMomentCount(entry.getKey(), entry.getValue())).toList();
    }

    /** 读取时光线首屏，按发生时间、发布时间和瞬间 ID 正序排列。 */
    public List<Moment> selectFirstTimelinePage(UUID albumId, UUID userId, int rowCount) {
        // 首屏无游标边界。
        return selectNextTimelinePage(albumId, userId, null, null, null, rowCount);
    }

    /** 读取游标之后的时光线，最多返回页大小加一条。 */
    public List<Moment> selectNextTimelinePage(UUID albumId, UUID userId, LocalDateTime memoryTime,
            LocalDateTime createdAt, UUID id, int rowCount) {
        // 对每批关联应用相同的过滤和游标，合并时只保留全局最靠前的候选。
        var ids = associations.selectActiveLinks(List.of(albumId)).stream().map(AlbumMoment::getMomentId).toList();
        List<Moment> page = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += MOMENT_BATCH_SIZE) {
            var batch = ids.subList(start, Math.min(start + MOMENT_BATCH_SIZE, ids.size()));
            page.addAll(moments.selectOwnedAlbumTimeline(batch, userId, memoryTime, createdAt, id, rowCount));
            page.sort(TIMELINE_ORDER);
            if (page.size() > rowCount) page.subList(rowCount, page.size()).clear();
        }
        return page;
    }
}
