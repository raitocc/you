package com.you.youbackend.circle.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** 圈子列表摘要，只展示名称和头像，ID 用于进入圈子详情。 */
@Schema(description = "圈子列表摘要")
public record CircleListResponse(
        @Schema(description = "圈子 ID，用于查看详情", format = "uuid") UUID id,
        @Schema(description = "圈子名称") String name,
        @Schema(description = "头像读取签名地址，未设置或失效时返回默认头像", format = "uri") String avatarUrl
) {}
