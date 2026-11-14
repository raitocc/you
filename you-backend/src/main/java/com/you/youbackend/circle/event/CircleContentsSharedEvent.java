package com.you.youbackend.circle.event;

import com.you.youbackend.circle.model.CircleSharedContent;
import java.util.List;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 本次实际新增或恢复的圈子分享，同步发布于原业务事务内。
 * @param actorUserId 本次分享者
 * @param contents 实际发生变化的分享引用
 * @param sharedAt 本次分享发生时间，UTC 毫秒精度
 */
public record CircleContentsSharedEvent(UUID actorUserId, List<CircleSharedContent> contents, LocalDateTime sharedAt) {
    /** 固定实际分享引用，避免后续步骤修改事件内容。 */
    public CircleContentsSharedEvent {
        contents = List.copyOf(contents);
    }
}
