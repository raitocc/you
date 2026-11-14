package com.you.youbackend.gathering.event;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

/**
 * 本次实际新增的同行瞬间关联，同步发布于原业务事务内。
 * @param gatheringId 目标同行
 * @param actorUserId 瞬间作者及关联操作者
 * @param momentIds 本次实际新增关联的瞬间 ID
 * @param sharedAt 关联发生时间，UTC 毫秒精度
 */
public record GatheringMomentsLinkedEvent(UUID gatheringId, UUID actorUserId, List<UUID> momentIds, LocalDateTime sharedAt) {
    /** 固定本次业务结果，后续处理沿用同一组瞬间 ID。 */
    public GatheringMomentsLinkedEvent {
        momentIds = List.copyOf(momentIds);
    }
}
