package com.you.youbackend.gathering.response;

import com.you.youbackend.user.response.PublicUserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 邀请码加入预览，只包含确认目标所需信息。
 * @param id 目标同行 UUIDv7
 * @param name 同行名称
 * @param description 同行介绍；未设置为 null
 * @param creator 创建者公开资料
 * @param isMember 当前用户是否为有效成员，为 true 时可以直接进入同行
 */
@Schema(description = "同行加入预览")
public record GatheringJoinPreviewResponse(
        @Schema(description = "目标同行 UUIDv7", format = "uuid")
        UUID id,

        @Schema(description = "同行名称")
        String name,

        @Schema(description = "同行介绍；未设置为 null", nullable = true)
        String description,

        @Schema(description = "创建者公开资料")
        PublicUserResponse creator,

        @Schema(description = "当前用户是否为有效成员；为 true 时可直接进入同行")
        boolean isMember
) {
}