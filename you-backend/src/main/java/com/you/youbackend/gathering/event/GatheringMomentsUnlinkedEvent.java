package com.you.youbackend.gathering.event;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

/**
 * 本次取消的同行瞬间关联，供订阅方撤销对应入口。
 * @param gatheringId 来源同行
 * @param momentIds 本次取消关联的瞬间 ID
 * @param removedAt 取消发生时间，UTC 毫秒精度
 */
public record GatheringMomentsUnlinkedEvent(UUID gatheringId, List<UUID> momentIds, LocalDateTime removedAt) {
    /** 固定本次业务结果，后续处理沿用同一组瞬间 ID。 */
    public GatheringMomentsUnlinkedEvent {
        momentIds = List.copyOf(momentIds);
    }
}
