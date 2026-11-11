package com.you.youbackend.circle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.CircleMemberHistory;
import org.apache.ibatis.annotations.Mapper;
import java.time.LocalDateTime;
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
}
