package com.you.youbackend.moment.service;

import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.response.AlbumSummaryResponse;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.common.enumeration.ObjectType;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.mapper.RelatedFeedMapper;
import com.you.youbackend.moment.model.RelatedFeedCursor;
import com.you.youbackend.moment.model.RelatedFeedRow;
import com.you.youbackend.moment.response.MomentSummaryResponse;
import com.you.youbackend.moment.response.RelatedFeedResponse;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 浏览“与你有关” Tab 相关业务方法
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class RelatedFeedService {

    private final RelatedFeedMapper relatedFeedMapper;
    private final MomentMapper momentMapper;
    private final AlbumMapper albumMapper;
    private final MomentService momentService;
    private final AlbumService albumService;
    private final UserService userService;

    /**
     * 浏览与你有关的动态流
     * @param userId 用户 ID
     * @param request 游标分页请求
     * @return 与你有关的分页 Page
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResultPage<RelatedFeedResponse, RelatedFeedCursor> browseRelated(UUID userId, PageRequest<RelatedFeedCursor> request) {

        // 1.根据分页查询游标查询 user_inbox 表得到当前 userId 信箱里的记录列表 rows
        RelatedFeedCursor cursor = request.cursor();
        int limit = request.limit();

        List<RelatedFeedRow> rows= relatedFeedMapper.selectPage(
                userId,
                cursor == null ? null : cursor.sharedAt(),
                cursor == null ? null : cursor.id(),
                limit + 1
        );

        // 空则返回空
        if (rows == null || rows.isEmpty()) {
            return new ResultPage<>(List.of(), null, false);
        }

        // 多查一条，判断后面是否还有记录
        boolean hasMore = rows.size() > limit;
        // 得到当前分页查询实际的页对象
        List<RelatedFeedRow> page = rows.subList(0, Math.min(rows.size(), limit));

        // 2.调用其他服务获取相关业务数据
        // 调用 userService，获取 Map(发布者用户 ID userId:发布者用户资料 PublicUserResponse) 的映射 actors
        Map<UUID, PublicUserResponse> actors = userService.getPublicUsers(
                page.stream()
                        .map(RelatedFeedRow::getActorUserId)
                        .distinct()
                        .toList()
        );
        // 获取该 page 里的瞬间 ID 列表
        List<UUID> momentIds = contentIds(page, ObjectType.moment);
        // 获取该 page 里的时光 ID 列表
        List<UUID> albumIds = contentIds(page, ObjectType.album);
        // 通过瞬间 ID 列表与瞬间服务，获取对应的瞬间实体列表
        List<Moment> moments = momentIds.isEmpty()
                ? List.of() : momentMapper.selectByIds(momentIds);
        // 通过时光 ID 列表与时光服务，获取对应的时光实体列表
        List<Album> albums = albumIds.isEmpty()
                ? List.of() : albumMapper.selectByIds(albumIds);
        // 通过瞬间服务，把 Moment 对象组装为 MomentSummaryResponse 对象，建立 Map(瞬间 ID：瞬间概要) 的映射
        Map<UUID, MomentSummaryResponse> momentSummaries =
                momentService.convertToSummaryResponse(moments).stream()
                        .collect(Collectors.toMap(
                                MomentSummaryResponse::id, Function.identity() // 表示对象本身
                        ));

        // 3.组装 RelatedFeedResponse 对象列表
        // 通过时光服务，把 Album 对象组装为 AlbumSummaryResponse 对象，建立 Map(时光 ID：时光概要) 的映射
        Map<UUID, AlbumSummaryResponse> albumSummaries =
                albumService.convertToSummaryResponse(albums).stream()
                        .collect(Collectors.toMap(
                                AlbumSummaryResponse::id, Function.identity() // 表示对象本身
                        ));

        // 构建返回对象列表 RelatedFeedResponse
        List<RelatedFeedResponse> items = page.stream()
                .map(row -> new RelatedFeedResponse(
                        row.getId(),
                        row.getSharedAt(),
                        row.getObjectType(),
                        actors.get(row.getActorUserId()),
                        row.getObjectType() == ObjectType.moment
                                ? momentSummaries.get(row.getObjectId()) : null,
                        row.getObjectType() == ObjectType.album
                                ? albumSummaries.get(row.getObjectId()) : null
                ))
                .toList();
        // 4.返回 ResultPage 分页对象
        // 构建返回游标 nextCursor、hasMore
        RelatedFeedCursor nextCursor = null;
        if (hasMore) {
            RelatedFeedRow last = page.get(page.size() - 1);
            nextCursor = new RelatedFeedCursor(
                    last.getSharedAt(), last.getId()
            );
        }
        return new ResultPage<>(items, nextCursor, hasMore);
    }

    /**
     * 辅助方法：从分页列表里挑出指定类型（如瞬间、时光）的数据，提取并返回它们的 ID 列表。
     *
     * 异常与注意：
     * 1. 入参 page 不能为 null，否则直接抛 NullPointerException；objectType 为 null 时只会过滤不出结果，不会报错。
     * 2. 纯查询方法，无任何副作用（不会修改原列表），也不会返回 null（没匹配到就返回空列表）。
     *
     * @param page       分页查询出来的原始列表（不能为 null）
     * @param objectType 想要筛选的类型（如：瞬间、时光）
     * @return           匹配到的对象 ID 列表（非 null）
     */
    private List<UUID> contentIds(List<RelatedFeedRow> page, ObjectType objectType) {
        return page.stream()
                .filter(row -> row.getObjectType() == objectType)
                .map(RelatedFeedRow::getObjectId)
                .toList();
    }


}
