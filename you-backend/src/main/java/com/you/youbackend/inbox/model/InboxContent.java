package com.you.youbackend.inbox.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 一次待投递的内容与来源，各接收者共用这些业务字段。
 * @param sourceType 来源空间类型
 * @param sourceId 来源空间 ID
 * @param actorUserId 本次关联或分享者
 * @param objectType 原内容类型
 * @param objectId 原内容 ID
 * @param shareId 圈子分享关系 ID；同行为 null
 * @param sharedAt 业务发生时间，UTC 毫秒精度
 */
public record InboxContent(InboxSourceType sourceType, UUID sourceId, UUID actorUserId,
                           InboxObjectType objectType, UUID objectId, UUID shareId, LocalDateTime sharedAt) {}
