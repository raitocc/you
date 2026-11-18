package com.you.youbackend.gathering.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 事件：一个或多个瞬间已加入同行
 * 发布入口：GatheringService.addMoments()、GatheringService.createMoment()
 * @param gatheringId 同行 ID
 * @param actorUserId 瞬间的作者 ID
 * @param momentIds 瞬间 ID 列表
 * @param sharedAt 瞬间分享到同行的时间
 */
public record GatheringMomentsLinkedEvent(
        UUID gatheringId,
        UUID actorUserId,
        List<UUID> momentIds,
        LocalDateTime sharedAt
) {
    public GatheringMomentsLinkedEvent {
        // 保存本次变更的不可变快照，避免调用方后续修改列表
        momentIds = List.copyOf(momentIds);
    }
}
