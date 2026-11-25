package com.you.youbackend.moment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 事件：一个瞬间已删除
 * 发布入口：MomentService.deleteMoment()
 * @param momentId 瞬间 ID
 * @param deletedAt 瞬间删除时间
 */
public record MomentDeletedEvent(
        UUID momentId,
        Instant deletedAt
) {
}