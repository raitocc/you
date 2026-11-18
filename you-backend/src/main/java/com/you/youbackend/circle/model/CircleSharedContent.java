package com.you.youbackend.circle.model;

import com.you.youbackend.common.enumeration.ObjectType;

import java.util.UUID;

/**
 * Model：圈子分享的一条记录
 */
public record CircleSharedContent(
        /**
         * 圈子 ID
         */
        UUID circleId,

        /**
         * 分享 ID
         */
        UUID shareId,

        /**
         * 分享类型：【瞬间，时光】
         */
        ObjectType objectType,

        /**
         * 分享对象的 ID：【瞬间 ID，时光 ID】
         */
        UUID objectId
) {
}
