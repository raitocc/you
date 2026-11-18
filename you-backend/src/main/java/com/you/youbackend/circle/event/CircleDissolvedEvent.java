package com.you.youbackend.circle.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 事件：一个圈子已解散
 * 发布入口：CircleService.dissolveCircle()
 * @param circleId 圈子 ID
 * @param dissolvedAt 圈子解散时间
 */
public record CircleDissolvedEvent(
        UUID circleId,
        LocalDateTime dissolvedAt
) {
}