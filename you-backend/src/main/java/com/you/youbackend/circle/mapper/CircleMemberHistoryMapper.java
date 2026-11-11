package com.you.youbackend.circle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.CircleMemberHistory;
import org.apache.ibatis.annotations.Mapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 含离开记录的成员关系查询和恢复。 */
@Mapper
public interface CircleMemberHistoryMapper extends BaseMapper<CircleMemberHistory> {
    /** 按唯一成员关系键读取当前或历史关系。 */
    default CircleMemberHistory find(UUID circleId, UUID userId) {
        return selectOne(Wrappers.<CircleMemberHistory>lambdaQuery()
                .eq(CircleMemberHistory::getCircleId, circleId).eq(CircleMemberHistory::getUserId, userId));
    }

    /** 只恢复仍然离开且允许重新加入的原关系。 */
    default int restore(UUID id, LocalDateTime now) {
        return update(Wrappers.<CircleMemberHistory>lambdaUpdate()
                .eq(CircleMemberHistory::getId, id).isNotNull(CircleMemberHistory::getLeftAt)
                .eq(CircleMemberHistory::getJoinRestricted, false)
                .set(CircleMemberHistory::getLeftAt, null).set(CircleMemberHistory::getUpdatedAt, now));
    }

    /** 读取全部当前加入限制，按限制时间和用户 ID 倒序排列。 */
    default List<CircleMemberHistory> selectRestrictions(UUID circleId) {
        // 限制时间与移除时间同时记录。
        return selectList(Wrappers.<CircleMemberHistory>lambdaQuery()
                .select(CircleMemberHistory::getUserId, CircleMemberHistory::getLeftAt)
                .eq(CircleMemberHistory::getCircleId, circleId)
                .eq(CircleMemberHistory::getJoinRestricted, true)
                .isNotNull(CircleMemberHistory::getLeftAt)
                .orderByDesc(CircleMemberHistory::getLeftAt, CircleMemberHistory::getUserId));
    }

    /** 清除仍存在的加入限制，保留原成员关系及离开时间。 */
    default int clearRestriction(UUID circleId, UUID userId, LocalDateTime now) {
        return update(Wrappers.<CircleMemberHistory>lambdaUpdate()
                .eq(CircleMemberHistory::getCircleId, circleId)
                .eq(CircleMemberHistory::getUserId, userId)
                .eq(CircleMemberHistory::getJoinRestricted, true)
                .set(CircleMemberHistory::getJoinRestricted, false)
                .set(CircleMemberHistory::getUpdatedAt, now));
    }
}
