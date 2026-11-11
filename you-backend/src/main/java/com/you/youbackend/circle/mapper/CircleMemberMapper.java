package com.you.youbackend.circle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.CircleMember;
import org.apache.ibatis.annotations.Mapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 有效成员关系读写，沿用全局逻辑删除。 */
@Mapper
public interface CircleMemberMapper extends BaseMapper<CircleMember> {
    /** 判断指定用户是否为圈子当前成员。 */
    default boolean isActiveMember(UUID circleId, UUID userId) {
        return exists(Wrappers.<CircleMember>lambdaQuery().eq(CircleMember::getCircleId, circleId)
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
}
