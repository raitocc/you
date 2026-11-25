package com.you.youbackend.gathering.event;


import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 事件：一个或多个瞬间已从同行中移出
 * 发布入口：GatheringService.removeMoments()
 * @param gatheringId 同行 ID
 * @param momentIds 瞬间 ID 列表
 * @param removedAt 一个或多个瞬间被移除的时间
 */
public record GatheringMomentsUnlinkedEvent(
        UUID gatheringId,
        List<UUID> momentIds,
        Instant removedAt
) {
    public GatheringMomentsUnlinkedEvent {
        // 保存实际解除关联的结果，避免调用方后续修改列表。
        momentIds = List.copyOf(momentIds);
    }
}
