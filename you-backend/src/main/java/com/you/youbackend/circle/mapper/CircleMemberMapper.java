package com.you.youbackend.circle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.CircleMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 圈子成员关系读写。 */
@Mapper
public interface CircleMemberMapper extends BaseMapper<CircleMember> {
    /** 读取成员关系，包括已退出或被移除的记录。 */
    @Select("""
            SELECT id, circle_id, user_id, created_at, updated_at, join_restricted, deleted_at
            FROM circle_member
            WHERE circle_id = #{circleId} AND user_id = #{userId}
            """)
    CircleMember findIncludingDeleted(@Param("circleId") UUID circleId, @Param("userId") UUID userId);

    /** 恢复已离开且允许重新加入的成员关系。 */
    @Update("""
            UPDATE circle_member
            SET deleted_at = NULL, updated_at = #{now}
            WHERE id = #{id} AND deleted_at IS NOT NULL AND join_restricted = FALSE
            """)
    int restore(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** 按移除时间和用户 ID 倒序读取受限成员。 */
    @Select("""
            SELECT user_id, deleted_at
            FROM circle_member
            WHERE circle_id = #{circleId} AND join_restricted = TRUE AND deleted_at IS NOT NULL
            ORDER BY deleted_at DESC, user_id DESC
            """)
    List<CircleMember> selectRestrictions(@Param("circleId") UUID circleId);

    /** 清除加入限制，保留离开时间。 */
    @Update("""
            UPDATE circle_member
            SET join_restricted = FALSE, updated_at = #{now}
            WHERE circle_id = #{circleId} AND user_id = #{userId} AND join_restricted = TRUE
            """)
    int clearRestriction(@Param("circleId") UUID circleId, @Param("userId") UUID userId,
                         @Param("now") LocalDateTime now);

    /** 判断指定用户是否为圈子当前成员。 */
    default boolean isActiveMember(UUID circleId, UUID userId) {
        return exists(Wrappers.<CircleMember>lambdaQuery()
                .eq(CircleMember::getCircleId, circleId)
                .eq(CircleMember::getUserId, userId));
    }

    /** 按成员关系时间和 ID 倒序读取候选记录。 */
    default List<CircleMember> selectPageCandidates(UUID userId, LocalDateTime joinedAt, UUID id, int limit) {
        return selectList(Wrappers.<CircleMember>lambdaQuery().eq(CircleMember::getUserId, userId)
                .and(joinedAt != null, q -> q.lt(CircleMember::getCreatedAt, joinedAt)
                        .or(tie -> tie.eq(CircleMember::getCreatedAt, joinedAt).lt(CircleMember::getId, id)))
                .orderByDesc(CircleMember::getCreatedAt, CircleMember::getId).last("LIMIT " + limit));
    }

    /** 读取全部有效成员，按关系创建时间和用户 ID 正序排列。 */
    default List<CircleMember> selectActiveMembers(UUID circleId) {
        // 沿用全局逻辑删除，已退出或被移除的关系不会进入详情。
        return selectList(Wrappers.<CircleMember>lambdaQuery()
                .select(CircleMember::getUserId, CircleMember::getCreatedAt)
                .eq(CircleMember::getCircleId, circleId)
                .orderByAsc(CircleMember::getCreatedAt, CircleMember::getUserId));
    }

    /** 统计指定圈子的有效成员数。 */
    default long countActiveMembers(UUID circleId) {
        return selectCount(Wrappers.<CircleMember>lambdaQuery().eq(CircleMember::getCircleId, circleId));
    }

    /** 从指定圈子中查出用户仍有有效关系的圈子 ID。 */
    default List<UUID> selectActiveCircleIds(UUID userId, Collection<UUID> ids) {
        return selectList(Wrappers.<CircleMember>lambdaQuery().select(CircleMember::getCircleId)
                .eq(CircleMember::getUserId, userId).in(CircleMember::getCircleId, ids))
                .stream().map(CircleMember::getCircleId).toList();
    }

    /** 批量读取候选分享者中仍为成员的用户 ID。 */
    default Set<UUID> selectActiveUserIds(UUID circleId, Collection<UUID> userIds) {
        return selectList(Wrappers.<CircleMember>lambdaQuery().select(CircleMember::getUserId)
                .eq(CircleMember::getCircleId, circleId).in(CircleMember::getUserId, userIds))
                .stream().map(CircleMember::getUserId).collect(Collectors.toSet());
    }

    /** 结束指定用户的有效成员关系，并按离开原因设置加入限制。 */
    default int endMembership(UUID circleId, UUID userId, boolean restricted, LocalDateTime now) {
        // MP 自动过滤已结束的关系，重复操作不会覆盖原离开时间。
        return update(Wrappers.<CircleMember>lambdaUpdate()
                .eq(CircleMember::getCircleId, circleId).eq(CircleMember::getUserId, userId)
                .set(CircleMember::getDeletedAt, now).set(CircleMember::getUpdatedAt, now)
                .set(CircleMember::getJoinRestricted, restricted));
    }

    /** 结束解散圈子中的全部有效成员关系。 */
    default int invalidateMemberships(UUID circleId, LocalDateTime now) {
        return update(Wrappers.<CircleMember>lambdaUpdate().eq(CircleMember::getCircleId, circleId)
                .set(CircleMember::getDeletedAt, now).set(CircleMember::getUpdatedAt, now));
    }
}
