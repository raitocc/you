package com.you.youbackend.gathering.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 事件：一名成员已退出同行
 * 发布入口：GatheringService.leaveGathering()
 * @param gatheringId 同行 ID
 * @param userId 用户 ID
 * @param leftAt 用户退出时间
 */
public record GatheringMemberLeftEvent(
        UUID gatheringId,
        UUID userId,
        Instant leftAt
) {
}
