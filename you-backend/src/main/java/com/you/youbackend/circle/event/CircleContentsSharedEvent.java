package com.you.youbackend.circle.event;

import com.you.youbackend.circle.model.CircleSharedContent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 事件：一条瞬间或时光分享到一个或多个圈子
 * 发布入口：CircleService.shareToCircles()
 * @param actorUserId 分享者的用户 ID
 * @param contents 本次实际变更的分享记录列表（新增或恢复）
 * @param sharedAt 分享时间
 */
public record CircleContentsSharedEvent(
        UUID actorUserId,
        List<CircleSharedContent> contents,
        Instant sharedAt
) {
    public CircleContentsSharedEvent {
        // 保存本次变更的不可变快照，避免调用方后续修改列表。
        contents = List.copyOf(contents);
    }
}
