package com.you.youbackend.circle.model;

import java.util.UUID;

/**
 * 本次新增或恢复的圈子分享引用，供业务事件传递。
 * @param circleId 接收分享的圈子 ID
 * @param shareId 圈子分享关系 ID
 * @param objectType 原内容类型
 * @param objectId 原瞬间或时光 ID
 */
public record CircleSharedContent(UUID circleId, UUID shareId, CircleShareObjectType objectType, UUID objectId) {}
