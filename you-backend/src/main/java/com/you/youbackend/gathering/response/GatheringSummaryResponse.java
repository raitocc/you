package com.you.youbackend.gathering.response;

import com.you.youbackend.user.response.PublicUserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 同行摘要，用于列表展示与加入目标辨认。
 * @param id 同行 UUIDv7
 * @param name 同行名称
 * @param description 介绍的前 100 个 Unicode 码点；未设置为 null
 * @param creator 创建者公开资料
 */
@Schema(description = "同行摘要")
public record GatheringSummaryResponse(
        @Schema(description = "同行 UUIDv7", format = "uuid") UUID id,
        @Schema(description = "同行名称") String name,
        @Schema(description = "介绍的前 100 个 Unicode 码点；未设置为 null", nullable = true)
        String description,
        @Schema(description = "创建者公开资料") PublicUserResponse creator
) {
}
