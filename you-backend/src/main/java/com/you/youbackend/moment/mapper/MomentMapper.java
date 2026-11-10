package com.you.youbackend.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.gathering.model.GatheringCount;
import com.you.youbackend.moment.entity.Moment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 访问瞬间主表。
 */
@Mapper
public interface MomentMapper extends BaseMapper<Moment> {
    /**
     * 批量读取本人有效瞬间的关联校验字段。
     * @param userId 当前作者 ID
     * @param ids 非空且已去重的瞬间 ID
     * @return 仅含 id、authorUserId、gatheringId 的投影实体，仅用于关联校验
     */
    default List<Moment> selectOwnedForGathering(UUID userId, List<UUID> ids) {
        // 限定作者并沿用逻辑删除过滤，缺少的候选由业务层统一判为无效。
        return selectList(Wrappers.<Moment>lambdaQuery()
                .select(Moment::getId, Moment::getAuthorUserId, Moment::getGatheringId)
                .eq(Moment::getAuthorUserId, userId).in(Moment::getId, ids));
    }

    /**
     * 将本人尚未关联同行的有效瞬间批量加入目标同行。
     * @param userId 已校验的作者 ID
     * @param gatheringId 已校验有效、进行中且本人已加入的目标同行
     * @param ids 非空且已筛选为尚未关联的瞬间 ID
     * @param updatedAt 本次统一 UTC 毫秒时间
     * @return 实际修改行数
     */
    default int associateGathering(UUID userId, UUID gatheringId, List<UUID> ids, LocalDateTime updatedAt) {
        // 仅更新无关联记录，避免覆盖校验后由另一请求建立的同行关联。
        return update(Wrappers.<Moment>lambdaUpdate()
                .eq(Moment::getAuthorUserId, userId).in(Moment::getId, ids)
                .isNull(Moment::getGatheringId)
                .set(Moment::getGatheringId, gatheringId).set(Moment::getUpdatedAt, updatedAt));
    }

    /**
     * 批量移出本人在指定同行中的瞬间。
     * @param userId 已校验的作者 ID
     * @param gatheringId 已校验有效且本人已加入的同行，允许已结束
     * @param ids 非空且已筛选为当前同行关联的瞬间 ID
     * @param updatedAt 本次统一 UTC 毫秒时间
     * @return 实际修改行数
     */
    default int removeGathering(UUID userId, UUID gatheringId, List<UUID> ids, LocalDateTime updatedAt) {
        // 操作范围限定当前同行，显式写 null 清空关联，原内容和其他同行关联保持原样。
        return update(Wrappers.<Moment>lambdaUpdate()
                .eq(Moment::getAuthorUserId, userId).eq(Moment::getGatheringId, gatheringId)
                .in(Moment::getId, ids)
                .set(Moment::getGatheringId, null).set(Moment::getUpdatedAt, updatedAt));
    }

    /**
     * 批量统计同行的有效瞬间数量，避免列表逐条查询。
     * @param ids 非空的本页同行 ID 集合
     * @return 有记录的同行及数量；无记录的同行按零解释
     */
    @Select("""
            <script>
            SELECT gathering_id, COUNT(*) AS count FROM moment
            WHERE deleted_at IS NULL AND gathering_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY gathering_id
            </script>
            """)
    List<GatheringCount> countByGatherings(
            @Param("ids") Collection<UUID> ids);

    /**
     * 统计当前仍关联到同行的有效瞬间，供同行信息展示使用。
     * @param gatheringId 已确认有效且当前用户可访问的同行 ID
     * @return 未删除的关联瞬间数量
     */
    @Select("""
            SELECT COUNT(*) FROM moment
            WHERE gathering_id = #{gatheringId} AND deleted_at IS NULL
            """)
    long countByGathering(@Param("gatheringId") UUID gatheringId);

    /**
     * 批量读取本人未删除瞬间的 ID，用于关联创建前校验；只投影 ID，不返回不完整实体。
     * @param userId 当前用户 ID
     * @param ids 非空且已去重的候选瞬间 ID
     * @return 有效且属于本人的瞬间 ID，逻辑删除由 MP 自动过滤
     */
    default List<UUID> selectOwnedIds(UUID userId, List<UUID> ids) {
        // 按当前用户和候选 ID 批量筛选，沿用逻辑删除过滤，最终只向业务层返回有效 ID。
        return selectList(Wrappers.<Moment>lambdaQuery()
                .select(Moment::getId)
                .eq(Moment::getAuthorUserId, userId)
                .in(Moment::getId, ids)).stream().map(Moment::getId).toList();
    }

    /**
     * 查询瞬间，包含已软删除的记录，用于删除时校验归属和识别重复请求。
     * 自定义查询绕过 MP 的逻辑删除过滤，直接返回完整实体。
     *
     * @param id 瞬间 ID
     * @return 瞬间实体，不存在时为 null
     */
    @Select("""
            SELECT id, author_user_id, gathering_id, title, body, mood, memory_time,
                latitude, longitude, location_name, location_address,
                created_at, updated_at, deleted_at
            FROM moment WHERE id = #{id}
            """)
    Moment selectIncludingDeleted(@Param("id") UUID id);

    /**
     * 软删除有效瞬间，同时记录本次修改时间；MP 自动过滤已删除记录。
     *
     * @param id 瞬间 ID
     * @param deletedAt 本次删除的 UTC 时间
     */
    default void softDelete(UUID id, LocalDateTime deletedAt) {
        update(Wrappers.<Moment>lambdaUpdate()
                .eq(Moment::getId, id)
                .set(Moment::getDeletedAt, deletedAt)
                .set(Moment::getUpdatedAt, deletedAt));
    }

    /**
     * 全量更新可编辑字段，允许显式清空可空字段。
     * 实体由 Service 完成校验和整理；显式 set 将 null 写入数据库，清空对应字段。
     */
    default void updateContent(Moment moment) {
        update(Wrappers.<Moment>lambdaUpdate()
                .eq(Moment::getId, moment.getId())
                .set(Moment::getTitle, moment.getTitle())
                .set(Moment::getBody, moment.getBody())
                .set(Moment::getMood, moment.getMood())
                .set(Moment::getMemoryTime, moment.getMemoryTime())
                .set(Moment::getLatitude, moment.getLatitude())
                .set(Moment::getLongitude, moment.getLongitude())
                .set(Moment::getLocationName, moment.getLocationName())
                .set(Moment::getLocationAddress, moment.getLocationAddress())
                .set(Moment::getUpdatedAt, moment.getUpdatedAt()));
    }

    /**
     * 读取本人瞬间流中位于游标之后的卡片数据。
     * 按发生时间、ID 双字段倒序排列，发生时间相同的记录按唯一 ID 确定顺序。
     *
     * @param userId 当前用户 ID，仅查询本人有效瞬间
     * @param cursorTime 游标的 UTC 入库时间，null 表示首屏
     * @param cursorId 游标记录 ID，由 Service 与 cursorTime 成对传入
     * @param rowCount 实际页大小加一，额外一条用于判断下一页
     * @return 仅填充卡片查询所需字段的瞬间列表
     * 返回实体仅含 id、title、body、mood、memoryTime，供卡片组装使用。
     */
    default List<Moment> selectFeed(UUID userId, LocalDateTime cursorTime, UUID cursorId, int rowCount) {
        var query = Wrappers.<Moment>lambdaQuery()
                .select(Moment::getId, Moment::getTitle, Moment::getBody, Moment::getMood, Moment::getMemoryTime)
                .eq(Moment::getAuthorUserId, userId);
        // 倒序分页仅查询更早的时间，或相同时间下更小的 ID，排除上一页边界及其之前的记录。
        if (cursorTime != null) {
            query.and(boundary -> boundary.lt(Moment::getMemoryTime, cursorTime)
                    .or(sameTime -> sameTime.eq(Moment::getMemoryTime, cursorTime)
                            .lt(Moment::getId, cursorId)));
        }
        return selectList(query.orderByDesc(Moment::getMemoryTime, Moment::getId).last("LIMIT " + rowCount));
    }
}
