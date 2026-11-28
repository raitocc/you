package com.you.youbackend.circle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.CircleMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 圈子成员关系读写，有效关系统一由全局逻辑删除过滤。 */
@Mapper
public interface CircleMemberMapper extends BaseMapper<CircleMember> {
    /**
     * 按圈子和用户查成员关系，包含已退出或被移除的记录（自定义 SQL，绕过 MP 的逻辑删除过滤）。
     * @param circleId 圈子 ID
     * @param userId 用户 ID
     * @return 成员关系（含软删历史），不存在时为 null
     */
    @Select("""
            SELECT id, circle_id, user_id, created_at, updated_at, join_restricted, deleted_at
            FROM circle_member
            WHERE circle_id = #{circleId} AND user_id = #{userId}
            """)
    CircleMember selectIncludingDeletedByCircleIdAndUserId(@Param("circleId") UUID circleId,
                                                           @Param("userId") UUID userId);

    /**
     * 恢复已离开且未被限制加入的成员关系，保留原 ID 与创建时间。
     * @param id 成员关系 ID
     * @param now 恢复时间，UTC，写入 updatedAt
     * @return 恢复成功返回 1；仍有效、被限制加入或关系已变化返回 0
     */
    @Update("""
            UPDATE circle_member
            SET deleted_at = NULL, updated_at = #{now}
            WHERE id = #{id} AND deleted_at IS NOT NULL AND join_restricted = FALSE
            """)
    int restore(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * 读取仍被限制再次加入的成员，按移除时间、用户 ID 倒序排列。
     * @param circleId 圈子 ID
     * @return 受限成员关系（只投影用户 ID 与移除时间），无受限成员时为空
     */
    @Select("""
            SELECT user_id, deleted_at
            FROM circle_member
            WHERE circle_id = #{circleId} AND join_restricted = TRUE AND deleted_at IS NOT NULL
            ORDER BY deleted_at DESC, user_id DESC
            """)
    List<CircleMember> selectRestrictedMembers(@Param("circleId") UUID circleId);

    /**
     * 清除加入限制，保留离开时间；没有限制时不做修改。
     * @param circleId 圈子 ID
     * @param userId 被解除限制的用户 ID
     * @param now 修改时间，UTC
     * @return 实际解除的行数
     */
    @Update("""
            UPDATE circle_member
            SET join_restricted = FALSE, updated_at = #{now}
            WHERE circle_id = #{circleId} AND user_id = #{userId} AND join_restricted = TRUE
            """)
    int clearRestriction(@Param("circleId") UUID circleId, @Param("userId") UUID userId,
                         @Param("now") Instant now);

    /**
     * 判断指定用户是否为圈子当前有效成员。
     * @param circleId 圈子 ID
     * @param userId 用户 ID
     * @return 有效成员返回 true，从未加入或已退出返回 false
     */
    default boolean existsByCircleIdAndUserId(UUID circleId, UUID userId) {
        // 已退出或移除的关系由全局逻辑删除条件过滤。
        return exists(Wrappers.<CircleMember>lambdaQuery()
                .eq(CircleMember::getCircleId, circleId)
                .eq(CircleMember::getUserId, userId));
    }

    /**
     * 按加入时间、圈子 ID 倒序读取本人的成员关系候选，给“我的圈子列表”翻页用。
     * 候选里可能含已解散圈子的关系，由调用方补齐主记录时过滤，因此按批次推进游标。
     * @param userId 当前用户 ID
     * @param joinedAt 游标加入时间，UTC；null 表示首屏
     * @param circleId 游标圈子 ID，与 joinedAt 成对提供
     * @param limit 本批最大行数
     * @return 倒序候选记录，软删关系由全局逻辑删除过滤
     */
    default List<CircleMember> selectPageAfterCursorByUserId(UUID userId, Instant joinedAt, UUID circleId, int limit) {
        return selectList(Wrappers.<CircleMember>lambdaQuery().eq(CircleMember::getUserId, userId)
                .and(joinedAt != null, q -> q.lt(CircleMember::getCreatedAt, joinedAt)
                        .or(tie -> tie.eq(CircleMember::getCreatedAt, joinedAt).lt(CircleMember::getCircleId, circleId)))
                .orderByDesc(CircleMember::getCreatedAt, CircleMember::getCircleId).last("LIMIT " + limit));
    }

    /**
     * 读取圈子全部有效成员，按关系创建时间、用户 ID 正序排列，供详情页展示。
     * @param circleId 已校验访问资格的圈子 ID
     * @return 有效成员关系（只投影用户 ID 与加入时间）
     */
    default List<CircleMember> selectActiveMembers(UUID circleId) {
        // 已退出或被移除的关系不会进入详情。
        return selectList(Wrappers.<CircleMember>lambdaQuery()
                .select(CircleMember::getUserId, CircleMember::getCreatedAt)
                .eq(CircleMember::getCircleId, circleId)
                .orderByAsc(CircleMember::getCreatedAt, CircleMember::getUserId));
    }

    /**
     * 批量读取各圈子分享的有效接收者，供收件箱等下游投递使用。
     * @param circleIds 目标圈子 ID 集合
     * @return 有效成员关系（只投影圈子 ID 与用户 ID），空集合返回空列表
     */
    default List<CircleMember> selectActiveRecipients(Collection<UUID> circleIds) {
        if (circleIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<CircleMember>lambdaQuery()
                .select(CircleMember::getCircleId, CircleMember::getUserId)
                .in(CircleMember::getCircleId, circleIds));
    }

    /**
     * 从候选圈子中筛出用户仍有有效成员关系的圈子 ID。
     * @param userId 当前用户 ID
     * @param ids 候选圈子 ID 集合
     * @return 仍是有效成员的圈子 ID 列表
     */
    default List<UUID> selectActiveCircleIds(UUID userId, Collection<UUID> ids) {
        return selectList(Wrappers.<CircleMember>lambdaQuery().select(CircleMember::getCircleId)
                .eq(CircleMember::getUserId, userId).in(CircleMember::getCircleId, ids))
                .stream().map(CircleMember::getCircleId).toList();
    }

    /**
     * 批量筛出候选用户中仍为该圈子有效成员的用户 ID。
     * @param circleId 圈子 ID
     * @param userIds 候选用户 ID 集合
     * @return 仍为有效成员的用户 ID 集合
     */
    default Set<UUID> selectActiveUserIds(UUID circleId, Collection<UUID> userIds) {
        return selectList(Wrappers.<CircleMember>lambdaQuery().select(CircleMember::getUserId)
                .eq(CircleMember::getCircleId, circleId).in(CircleMember::getUserId, userIds))
                .stream().map(CircleMember::getUserId).collect(Collectors.toSet());
    }

    /**
     * 结束指定用户的有效成员关系，并按离开原因写入加入限制。
     * 主动退出不限制（false），被移除则限制再次加入（true）。
     * @param circleId 圈子 ID
     * @param userId 成员用户 ID
     * @param restricted 是否限制再次加入
     * @param now 离开时间，UTC，同时写入 deletedAt 和 updatedAt
     * @return 实际结束的行数；已是离开状态时为 0，不覆盖原离开时间
     */
    default int endMembership(UUID circleId, UUID userId, boolean restricted, Instant now) {
        // MP 自动过滤已结束的关系，重复操作不会覆盖原离开时间。
        return update(Wrappers.<CircleMember>lambdaUpdate()
                .eq(CircleMember::getCircleId, circleId).eq(CircleMember::getUserId, userId)
                .set(CircleMember::getDeletedAt, now).set(CircleMember::getUpdatedAt, now)
                .set(CircleMember::getJoinRestricted, restricted));
    }

    /**
     * 结束解散圈子中的全部有效成员关系，不写入加入限制。
     * @param circleId 圈子 ID
     * @param now 解散时间，UTC，同时写入 deletedAt 和 updatedAt
     * @return 实际结束的行数
     */
    default int endAllMemberships(UUID circleId, Instant now) {
        return update(Wrappers.<CircleMember>lambdaUpdate()
                .eq(CircleMember::getCircleId, circleId)
                .set(CircleMember::getDeletedAt, now)
                .set(CircleMember::getUpdatedAt, now));
    }
}
