package com.you.youbackend.circle.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 事件：一名成员离开圈子或被圈子移除
 * 发布入口：CircleService.leaveCircle()、CircleService.removeMember()
 * @param circleId 圈子 ID
 * @param userId 用户 ID
 * @param leftAt 离开时间
 */
public record CircleMemberLeftEvent(
        UUID circleId,
        UUID userId,
        Instant leftAt
) {
}
