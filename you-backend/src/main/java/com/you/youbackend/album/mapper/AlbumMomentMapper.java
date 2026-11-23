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

import java.util.HashMap;
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
     * 把指定瞬间从本时光移出（软删关联）；没加过和已移出的自然跳过。原瞬间和其他时光的关联不动。
     *
     * @param albumId   本人有效时光 ID
     * @param momentIds 非空、已去重的瞬间 ID
     * @param savedAt   本次统一的 UTC 时间，写入 deletedAt 和 updatedAt
     */
    default void softDeleteByAlbumIdAndMomentIdIn(UUID albumId, List<UUID> momentIds, LocalDateTime savedAt) {
        // 仅更新当前时光中的有效关联，保留原瞬间及其他时光关系。
        update(Wrappers.<AlbumMoment>lambdaUpdate()
                .eq(AlbumMoment::getAlbumId, albumId)
                .in(AlbumMoment::getMomentId, momentIds)
                .set(AlbumMoment::getDeletedAt, savedAt).set(AlbumMoment::getUpdatedAt, savedAt));
    }

    /**
     * 解除本时光的全部有效关联（软删）。原瞬间、媒体和其他时光的关联都保留。
     *
     * @param albumId   已校验归属的时光 ID
     * @param deletedAt 本次统一的 UTC 毫秒时间
     */
    default void softDeleteByAlbumId(UUID albumId, LocalDateTime deletedAt) {
        // MP 过滤已删除关联，保留历史移出时间，不访问原瞬间和媒体表。
        update(Wrappers.<AlbumMoment>lambdaUpdate()
                .eq(AlbumMoment::getAlbumId, albumId)
                .set(AlbumMoment::getDeletedAt, deletedAt)
                .set(AlbumMoment::getUpdatedAt, deletedAt));
    }

    /**
     * 批量“新增或恢复”时光关联，一条 SQL 处理三种情况：
     * 从没加过 → 插入新关联；加过又移出 → 恢复，保留原 ID 和创建时间、刷新修改时间；还在时光里 → 原样不动。
     *
     * <p>副作用：写 album_moment 表，跟随外层事务。恢复时不采用传入的新 ID 和创建时间。
     * 调用方必须保证 associations 非空：空列表会让 foreach 拼出非法 SQL，本方法不做空守卫。</p>
     *
     * @param associations 待写入的关联实体，albumId、momentId、审计时间已填好，注意必须非空
     * @param savedAt      本次统一时间，恢复时写入 updated_at
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

    /**
     * 数本时光的有效关联数（数的是关联，不是瞬间本身）。
     *
     * @param albumId 时光 ID
     * @return 有效关联数，没有记 0
     */
    default long countMomentsByAlbumId(UUID albumId) {
        return selectCount(Wrappers.<AlbumMoment>lambdaQuery()
                .eq(AlbumMoment::getAlbumId, albumId));
    }

    /**
     * 批量数多个时光的有效关联数，只返回有关联的时光（GROUP BY 没行就没记录）。
     *
     * @param albumIds 时光 ID 集合
     * @return 时光 ID → 有效关联数，不含数量为 0 的时光；需要全量用补零版 countMomentsByAlbumIds
     */
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


    /**
     * 批量数多个时光的有效关联数，没关联的补 0，调用方免判缺项。
     *
     * @param albumIds 时光 ID 集合
     * @return 时光 ID → 有效关联数的映射，入参里每个 ID 都有值
     */
    default Map<UUID, Long> countMomentsByAlbumIds(List<UUID> albumIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (albumIds.isEmpty()) {
            return counts;
        }

        for (UUID albumId : albumIds) {
            counts.put(albumId, 0L);
        }
        for (AlbumMomentCount row : selectMomentCountsByAlbumIds(albumIds)) {
            counts.put(row.albumId(), row.momentCount());
        }
        return counts;
    }

    // TODO 业务要保证删除的时候 清理掉关联表中的关联

    /**
     * 查时光内排在游标后面的一页瞬间，给时光线分页用。
     * 只看本人瞬间和有效关联；瞬间的软删状态不过滤——业务保证删除瞬间时同步清理关联。
     *
     * <p>按“发生时间、ID 正序（从早到晚）”排，游标条件是“时间更晚，或时间相同且 ID 更大”。
     * 只读查询，无副作用。</p>
     *
     * @param albumId    时光 ID
     * @param userId     瞬间作者 ID，只返回本人的瞬间
     * @param cursorTime 游标的 UTC 时间，null 表示从最早开始
     * @param cursorId   游标瞬间 ID，和 cursorTime 成对传入
     * @param rowCount   本页大小 + 1，多的那条只用来判断下一页
     * @return 部分字段的瞬间实体（ID、作者、标题、正文、心情、发生时间），旧到新排列
     */
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

    /**
     * 执行时光线查询：按包装器条件 join 瞬间表取卡片字段，限制行数。查询条件由调用方拼好。
     *
     * @param query    带表前缀的查询条件（am. / m.）
     * @param rowCount 最大返回行数
     * @return 瞬间部分实体列表
     */
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
     * 判断瞬间是否还在指定时光里（存在有效关联）。
     *
     * @param albumId  已通过来源授权的时光 ID
     * @param momentId 待查的瞬间 ID
     * @return 有有效关联返回 true
     */
    default boolean hasActiveAssociation(UUID albumId, UUID momentId) {
        // 只检查关系；瞬间状态与作者由调用方读取原内容后校验。
        return exists(Wrappers.<AlbumMoment>lambdaQuery()
                .eq(AlbumMoment::getAlbumId, albumId)
                .eq(AlbumMoment::getMomentId, momentId));
    }

    /**
     * 批量查多个时光的有效关联，只投影两个 ID，给查共享瞬间用。
     *
     * @param albumIds 时光 ID 集合
     * @return 有效关联的 albumId、momentId 投影列表
     */
    default List<AlbumMoment> selectActiveLinks(List<UUID> albumIds) {
        return selectList(Wrappers.<AlbumMoment>lambdaQuery()
                .select(AlbumMoment::getAlbumId, AlbumMoment::getMomentId)
                .in(AlbumMoment::getAlbumId, albumIds));
    }

    default void removeAssociationsByMomentId(UUID momentId, LocalDateTime deletedAt) {
        update(Wrappers.<AlbumMoment>lambdaUpdate()
                .eq(AlbumMoment::getMomentId, momentId)
                .set(AlbumMoment::getDeletedAt, deletedAt)
                .set(AlbumMoment::getUpdatedAt, deletedAt));
    }
}
