package com.you.youbackend.circle.model;

import com.you.youbackend.common.enumeration.ObjectType;

import java.util.UUID;

/**
 * 圈子分享事件里的一条变更记录：哪个圈子的哪条分享指向哪个原内容。
 * @param circleId 目标圈子 ID
 * @param shareId 分享关系 ID
 * @param objectType 原内容类型，瞬间或时光
 * @param objectId 原内容 ID，瞬间 ID 或时光 ID
 */
public record CircleSharedContent(
        UUID circleId,
        UUID shareId,
        ObjectType objectType,
        UUID objectId
) {
}
