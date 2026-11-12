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
import java.util.UUID;
import java.time.LocalDateTime;

/** 时光瞬间关联的批量写入和有效数量查询。 */
@Mapper
public interface AlbumMomentMapper extends BaseMapper<AlbumMoment> {
    /**
     * 批量读取候选关联，包含已移出记录，用于区分新增、保留与恢复。
     * @param albumId 本人有效时光 ID
     * @param momentIds 非空且已去重的瞬间 ID
     * @return 完整历史及有效关联
     */
    @Select("""
            <script>
            SELECT * FROM album_moment WHERE album_id = #{albumId} AND moment_id IN
            <foreach collection="momentIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<AlbumMoment> selectIncludingDeleted(@Param("albumId") UUID albumId,
                                            @Param("momentIds") List<UUID> momentIds);

    /**
     * 恢复当前时光中的历史关联，保留原 ID 和创建时间；自定义 SQL 绕过逻辑删除过滤。
     * @param albumId 本人有效时光 ID
     * @param momentIds 非空的待恢复瞬间 ID
     * @param savedAt 本次统一 UTC 时间
     */
    @Update("""
            <script>
            UPDATE album_moment SET deleted_at = NULL, updated_at = #{savedAt}
            WHERE album_id = #{albumId} AND deleted_at IS NOT NULL AND moment_id IN
            <foreach collection="momentIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    void restoreAssociations(@Param("albumId") UUID albumId, @Param("momentIds") List<UUID> momentIds,
                             @Param("savedAt") LocalDateTime savedAt);

    /**
     * 移出当前时光内指定的有效关联，未加入及已移出项跳过。
     * @param albumId 本人有效时光 ID
     * @param momentIds 非空且已去重的瞬间 ID
     * @param savedAt 本次统一 UTC 时间
     */
    default void removeSelectedAssociations(UUID albumId, List<UUID> momentIds, LocalDateTime savedAt) {
        // 仅更新当前时光中的有效关联，保留原瞬间及其他时光关系。
        update(Wrappers.<AlbumMoment>lambdaUpdate().eq(AlbumMoment::getAlbumId, albumId)
                .in(AlbumMoment::getMomentId, momentIds)
                .set(AlbumMoment::getDeletedAt, savedAt).set(AlbumMoment::getUpdatedAt, savedAt));
    }

    /**
     * 批量解除指定时光的全部有效关联，原瞬间和其他时光的关联保留。
     * @param albumId 已校验归属的时光 ID
     * @param deletedAt 与主记录一致的 UTC 毫秒时间
     */
    default void removeAssociations(UUID albumId, LocalDateTime deletedAt) {
        // MP 过滤已删除关联，保留历史移出时间，不访问原瞬间和媒体表。
        update(Wrappers.<AlbumMoment>lambdaUpdate().eq(AlbumMoment::getAlbumId, albumId)
                .set(AlbumMoment::getDeletedAt, deletedAt).set(AlbumMoment::getUpdatedAt, deletedAt));
    }

    /**
     * 批量统计本页时光中的有效本人瞬间，自定义 SQL 显式过滤两张表的软删除。
     * @param albumIds 已确认属于本人的本页非空时光 ID 集合
     * @param userId 时光所有者 ID
     * @return 分组数量，无有效关联的时光无结果，由业务层补 0
     */
    @Select("""
            <script>
            SELECT am.album_id, COUNT(*) AS moment_count
            FROM album_moment am JOIN moment m ON m.id = am.moment_id
            WHERE am.deleted_at IS NULL AND m.deleted_at IS NULL AND m.author_user_id = #{userId}
              AND am.album_id IN
              <foreach collection="albumIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY am.album_id
            </script>
            """)
    List<AlbumMomentCount> countValidMomentsByAlbum(@Param("albumIds") List<UUID> albumIds, @Param("userId") UUID userId);

    /**
     * 分页读取时光中的本人有效瞬间，按发生时间、创建时间和 ID 正序排列。
     * @param albumId 已校验存在且属于当前用户的时光 ID
     * @param userId 当前用户 ID
     * @param cursorTime 游标发生时间，UTC 毫秒精度；null 表示首屏
     * @param cursorCreatedAt 游标创建时间，与发生时间和 ID 成组提供
     * @param cursorId 游标瞬间 ID
     * @param rowCount 已校验页大小加一，用于判断下一页
     * @return 有序完整瞬间实体，图片和标签另行加载
     */
    default List<Moment> selectTimelinePage(UUID albumId, UUID userId, LocalDateTime cursorTime,
                                           LocalDateTime cursorCreatedAt, UUID cursorId, int rowCount) {
        // 1. 限定时光和作者；自定义 SQL 显式过滤两张表的逻辑删除。
        var query = new QueryWrapper<Moment>()
                .eq("am.album_id", albumId)
                .eq("m.author_user_id", userId)
                .isNull("am.deleted_at")
                .isNull("m.deleted_at");
        // 2. 有游标时按三个排序字段依次比较，严格排除上一页末项。
        if (cursorTime != null) {
            query.and(boundary -> boundary.gt("m.memory_time", cursorTime)
                    .or(sameTime -> sameTime.eq("m.memory_time", cursorTime)
                            .gt("m.created_at", cursorCreatedAt))
                    .or(sameTimes -> sameTimes.eq("m.memory_time", cursorTime)
                            .eq("m.created_at", cursorCreatedAt).gt("m.id", cursorId)));
        }
        // 3. 排序与边界一致，交给固定关联 SQL 多取一条判断下一页。
        query.orderByAsc("m.memory_time", "m.created_at", "m.id");
        return selectTimelineRows(query, rowCount);
    }

    /**
     * 执行固定时光关联查询；筛选与排序由 selectTimelinePage 统一构造。
     * @param query 后端构造的带表别名条件，包含 WHERE、ORDER BY 和显式软删除过滤
     * @param rowCount 已校验页大小加一
     * @return 有序完整瞬间实体，关联表字段不进入结果
     */
    @Select("""
            SELECT m.id, m.author_user_id, m.title, m.body, m.mood,
                   m.memory_time, m.latitude, m.longitude,
                   m.location_name, m.location_address,
                   m.created_at, m.updated_at, m.deleted_at
            FROM album_moment am
            JOIN moment m ON m.id = am.moment_id
            ${ew.customSqlSegment}
            LIMIT #{rowCount}
            """)
    List<Moment> selectTimelineRows(@Param(Constants.WRAPPER) Wrapper<Moment> query,
                                    @Param("rowCount") int rowCount);

    /**
     * 一条 SQL 插入本次新建时光的关联，参与调用方事务，不恢复或忽略冲突。
     * @param associations 非空、已去重且完成业务校验的关联，含 ID 和 UTC 审计时间
     * @return 插入行数
     */
    @Insert("""
            <script>
            INSERT INTO album_moment (id, album_id, moment_id, created_at, updated_at)
            VALUES
            <foreach collection="associations" item="item" separator=",">
                (#{item.id}, #{item.albumId}, #{item.momentId}, #{item.createdAt}, #{item.updatedAt})
            </foreach>
            </script>
            """)
    int insertAssociations(@Param("associations") List<AlbumMoment> associations);

    /**
     * 统计指定时光中有效的本人瞬间，显式过滤关联和瞬间的软删除记录。
     * @param albumId 已确认有效且归属当前用户的时光 ID
     * @param userId 时光所有者 ID，排除非本人发布的瞬间
     * @return 当前有效瞬间数量，不依赖创建时的关联数
     */
    @Select("""
            SELECT COUNT(*) FROM album_moment am
            JOIN moment m ON m.id = am.moment_id
            WHERE am.album_id = #{albumId} AND am.deleted_at IS NULL
                AND m.deleted_at IS NULL AND m.author_user_id = #{userId}
            """)
    long countValidMoments(@Param("albumId") UUID albumId, @Param("userId") UUID userId);
    /** 读取目标时光的有效关联，仅投影用于查询共享瞬间的两个 ID。 */
    default List<AlbumMoment> selectActiveLinks(List<UUID> albumIds) {
        return selectList(Wrappers.<AlbumMoment>lambdaQuery()
                .select(AlbumMoment::getAlbumId, AlbumMoment::getMomentId)
                .in(AlbumMoment::getAlbumId, albumIds));
    }
}
