package com.you.youbackend.gathering.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 事件：一个同行已解散
 * 发布入口：GatheringService.dissolveGathering()
 * @param gatheringId 同行 ID
 * @param dissolvedAt 同行解散时间
 */
public record GatheringDissolvedEvent(
        UUID gatheringId,
        LocalDateTime dissolvedAt
) {
}