package com.you.youbackend.gathering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.gathering.entity.GatheringMember;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 同行成员表读写，复用全局 UUID 映射与软删除配置。 */
@Mapper
public interface GatheringMemberMapper extends BaseMapper<GatheringMember> {
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