package com.you.youbackend.album.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.album.entity.AlbumMoment;
import com.you.youbackend.album.model.AlbumMomentCount;
import com.you.youbackend.moment.entity.Moment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * 时光瞬间关联的批量写入和有效数量查询。
 */
@Mapper
public interface AlbumMomentMapper extends BaseMapper<AlbumMoment> {

    /**
     * 移出当前时光内指定的有效关联，未加入及已移出项跳过。
     *
     * @param albumId   本人有效时光 ID
     * @param momentIds 非空且已去重的瞬间 ID
     * @param savedAt   本次统一 UTC 时间
     */
    default void softDeleteByAlbumIdAndMomentIdIn(UUID albumId, List<UUID> momentIds, LocalDateTime savedAt) {
        // 仅更新当前时光中的有效关联，保留原瞬间及其他时光关系。
        update(Wrappers.<AlbumMoment>lambdaUpdate()
                .eq(AlbumMoment::getAlbumId, albumId)
                .in(AlbumMoment::getMomentId, momentIds)
                .set(AlbumMoment::getDeletedAt, savedAt).set(AlbumMoment::getUpdatedAt, savedAt));
    }

    /**
     * 批量解除指定时光的全部有效关联，原瞬间和其他时光的关联保留。
     *
     * @param albumId   已校验归属的时光 ID
     * @param deletedAt 与主记录一致的 UTC 毫秒时间
     */
    default void softDeleteByAlbumId(UUID albumId, LocalDateTime deletedAt) {
        // MP 过滤已删除关联，保留历史移出时间，不访问原瞬间和媒体表。
        update(Wrappers.<AlbumMoment>lambdaUpdate()
                .eq(AlbumMoment::getAlbumId, albumId)
                .set(AlbumMoment::getDeletedAt, deletedAt)
                .set(AlbumMoment::getUpdatedAt, deletedAt));
    }

    /**
     * 从没加过：插入新关联。
     * 加过又移除了：恢复旧关联，保留原 ID 和 created_at，更新 updated_at。
     * 已经在时光里：原值保持不变。
     */
    @Insert("""
            <script>
            INSERT INTO album_moment (id, album_id, moment_id, created_at, updated_at)
            VALUES
            <foreach collection="associations" item="item" separator=",">
                (#{item.id}, #{item.albumId}, #{item.momentId},
                 #{item.createdAt}, #{item.updatedAt})
            </foreach>
            ON DUPLICATE KEY UPDATE
                updated_at = IF(deleted_at IS NULL, updated_at, #{savedAt}),
                deleted_at = NULL
            </script>
            """)
    void insertOrRestoreAssociations(
            @Param("associations") List<AlbumMoment> associations,
            @Param("savedAt") LocalDateTime savedAt);

    // 包括原来moment已失效的！
    default long countMomentsByAlbumId(UUID albumId) {
        return selectCount(Wrappers.<AlbumMoment>lambdaQuery()
                .eq(AlbumMoment::getAlbumId, albumId));
    }

    @Select("""
            <script>
            SELECT album_id AS albumId, COUNT(*) AS momentCount
            FROM album_moment
            WHERE deleted_at IS NULL
              AND album_id IN
              <foreach collection="albumIds" item="id" open="(" separator="," close=")">
                  #{id}
              </foreach>
            GROUP BY album_id
            </script>
            """)
    List<AlbumMomentCount> selectMomentCountsByAlbumIds(
            @Param("albumIds") List<UUID> albumIds);

    default Map<UUID, Long> countMomentsByAlbumIds(List<UUID> albumIds) {
        return selectMomentCountsByAlbumIds(albumIds).stream()
                .collect(Collectors.toMap(
                        AlbumMomentCount::albumId,
                        AlbumMomentCount::momentCount));
    }

    // TODO 业务要保证删除的时候 清理掉关联表中的关联
    default List<Moment> selectPageByAlbumIdAndUserIdAfterCursor(
            UUID albumId, UUID userId,
            LocalDateTime cursorTime, UUID cursorId, int rowCount) {
        var query = new QueryWrapper<Moment>()
                .eq("am.album_id", albumId)
                .isNull("am.deleted_at")
                .eq("m.author_user_id", userId);

        if (cursorTime != null) {
            query.and(after -> after.gt("m.memory_time", cursorTime)
                    .or(sameTime -> sameTime.eq("m.memory_time", cursorTime).gt("m.id", cursorId)));
        }

        query.orderByAsc("m.memory_time", "m.id");
        return selectTimelineRows(query, rowCount);
    }

    @Select("""
        SELECT m.id, m.author_user_id, m.title, m.body, m.mood,
               m.memory_time, m.deleted_at
        FROM album_moment am
        JOIN moment m ON m.id = am.moment_id
        ${ew.customSqlSegment}
        LIMIT #{rowCount}
        """)
    List<Moment> selectTimelineRows(
            @Param(Constants.WRAPPER) Wrapper<Moment> query,
            @Param("rowCount") int rowCount);

    /**
     * 判断瞬间是否仍关联到指定时光，沿用 MP 对关联记录的逻辑删除过滤。
     *
     * @param albumId  已通过来源授权的有效时光 ID
     * @param momentId 待查看的瞬间 ID
     * @return 当前存在有效关联时为 true
     */
    default boolean hasActiveAssociation(UUID albumId, UUID momentId) {
        // 只检查关系；瞬间状态与作者由调用方读取原内容后校验。
        return exists(Wrappers.<AlbumMoment>lambdaQuery()
                .eq(AlbumMoment::getAlbumId, albumId)
                .eq(AlbumMoment::getMomentId, momentId));
    }

    /**
     * 读取目标时光的有效关联，仅投影用于查询共享瞬间的两个 ID。
     */
    default List<AlbumMoment> selectActiveLinks(List<UUID> albumIds) {
        return selectList(Wrappers.<AlbumMoment>lambdaQuery()
                .select(AlbumMoment::getAlbumId, AlbumMoment::getMomentId)
                .in(AlbumMoment::getAlbumId, albumIds));
    }
}
