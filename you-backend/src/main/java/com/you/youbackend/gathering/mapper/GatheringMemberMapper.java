package com.you.youbackend.gathering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.gathering.entity.GatheringMember;
import com.you.youbackend.gathering.model.GatheringCount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 同行成员表读写，复用全局 UUID 映射与软删除配置。 */
@Mapper
public interface GatheringMemberMapper extends BaseMapper<GatheringMember> {
    /**
     * 读取同行有效成员首屏，按关系创建时间、用户 ID 正序。
     * @param gatheringId 已校验访问资格的同行 ID
     * @param rowCount 页大小加一
     * @return 完整成员关系，用户资料由 Service 批量加载
     */
    default List<GatheringMember> selectFirstPage(UUID gatheringId, int rowCount) {
        // 关系的逻辑删除由全局配置过滤，重新加入保持原创建时间。
        return selectList(Wrappers.<GatheringMember>lambdaQuery()
                .eq(GatheringMember::getGatheringId, gatheringId)
                .orderByAsc(GatheringMember::getCreatedAt, GatheringMember::getUserId).last("LIMIT " + rowCount));
    }

    /**
     * 读取同行有效成员后续页。
     * @param gatheringId 已校验访问资格的同行 ID
     * @param createdAt 上一页末项关系创建时间，UTC
     * @param userId 上一页末项用户 ID
     * @param rowCount 页大小加一
     * @return 与首屏相同排序的完整成员关系
     */
    default List<GatheringMember> selectNextPage(UUID gatheringId, LocalDateTime createdAt,
                                                  UUID userId, int rowCount) {
        // 正序分页读取更晚的时间，或同时间下更大的用户 ID。
        return selectList(Wrappers.<GatheringMember>lambdaQuery()
                .eq(GatheringMember::getGatheringId, gatheringId)
                .and(boundary -> boundary.gt(GatheringMember::getCreatedAt, createdAt)
                        .or(sameTime -> sameTime.eq(GatheringMember::getCreatedAt, createdAt)
                                .gt(GatheringMember::getUserId, userId)))
                .orderByAsc(GatheringMember::getCreatedAt, GatheringMember::getUserId).last("LIMIT " + rowCount));
    }

    /**
     * 批量统计同行的有效成员数量，避免列表逐条查询。
     * @param ids 非空的本页同行 ID 集合
     * @return 有记录的同行及数量；无记录的同行按零解释
     */
    @Select("""
            <script>
            SELECT gathering_id, COUNT(*) AS count FROM gathering_member
            WHERE deleted_at IS NULL AND gathering_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY gathering_id
            </script>
            """)
    List<GatheringCount> countByGatherings(
            @Param("ids") Collection<UUID> ids);

    /**
     * 批量判断当前用户在候选同行中的有效成员资格。
     * @param ids 非空的候选同行 ID
     * @param userId 当前认证用户 ID
     * @return 已加入的同行 ID 集合，只投影 gathering_id
     */
    default Set<UUID> selectActiveGatheringIds(Collection<UUID> ids, UUID userId) {
        // 批量查询避免候选列表逐项查成员，投影结果只提取同行 ID。
        return selectList(Wrappers.<GatheringMember>lambdaQuery()
                .select(GatheringMember::getGatheringId)
                .in(GatheringMember::getGatheringId, ids)
                .eq(GatheringMember::getUserId, userId))
                .stream().map(GatheringMember::getGatheringId).collect(Collectors.toSet());
    }

    /**
     * 判断用户是否为当前同行的有效成员。
     * @param gatheringId 已确认有效的同行 ID
     * @param userId 当前用户 ID
     * @return 是否存在未软删除的成员关系
     */
    default boolean isActiveMember(UUID gatheringId, UUID userId) {
        // 只判断当前关系，已退出或移除记录由全局逻辑删除条件过滤。
        return selectCount(Wrappers.<GatheringMember>lambdaQuery()
                .eq(GatheringMember::getGatheringId, gatheringId)
                .eq(GatheringMember::getUserId, userId)) > 0;
    }

    /**
     * 统计同行中的有效成员数量。
     * @param gatheringId 已确认有效的同行 ID
     * @return 未软删除的成员关系数量，包含创建者
     */
    default long countActiveMembers(UUID gatheringId) {
        // 统计使用与成员访问一致的逻辑删除规则。
        return selectCount(Wrappers.<GatheringMember>lambdaQuery()
                .eq(GatheringMember::getGatheringId, gatheringId));
    }

    /**
     * 新增或恢复成员关系；唯一索引保证重复提交仍为同一条关系。
     * 冲突时保留原 ID 与创建时间，仅在原关系已退出时刷新修改时间并清空删除时间。
     * updated_at 必须在 deleted_at 清空前计算，以区分恢复与重复加入。
     * @param member 含新 UUIDv7、同行、用户及统一 UTC 时间的候选关系，deletedAt 为 null
     */
    @Insert("""
            INSERT INTO gathering_member (id, gathering_id, user_id, created_at, updated_at, deleted_at)
            VALUES (#{member.id}, #{member.gatheringId}, #{member.userId},
                    #{member.createdAt}, #{member.updatedAt}, NULL)
            ON DUPLICATE KEY UPDATE
                updated_at = IF(deleted_at IS NULL, updated_at, #{member.updatedAt}),
                deleted_at = NULL
            """)
    void joinOrRestore(@Param("member") GatheringMember member);
}
