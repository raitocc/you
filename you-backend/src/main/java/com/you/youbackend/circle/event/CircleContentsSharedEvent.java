package com.you.youbackend.circle.event;

import com.you.youbackend.circle.model.CircleSharedContent;
import com.you.youbackend.circle.request.CreateCircleSharesRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 事件：一条瞬间或时光分享到一个或多个圈子
 * 发布入口：
 * @see com.you.youbackend.circle.service.CircleService#shareToCircles(CreateCircleSharesRequest, UUID)
 */
public record CircleContentsSharedEvent(
        // 分享者的用户 ID
        UUID actorUserId,

        // 分享记录列表
        List<CircleSharedContent> contents,

        // 分享时间
        LocalDateTime sharedAt
) {
    public CircleContentsSharedEvent {
        // 保存此次业务变更的不可变快照。
        contents = List.copyOf(contents);
    }
}
