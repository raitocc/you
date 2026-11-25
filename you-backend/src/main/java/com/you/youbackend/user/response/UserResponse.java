package com.you.youbackend.user.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 当前用户资料。
 */
@Schema(description = "当前用户资料")
public record UserResponse(

        @Schema(description = "用户 UUIDv7")
        UUID id,

        @Schema(description = "本人登录账号")
        String username,

        @Schema(description = "昵称")
        String nickname,

        @Schema(description = "头像文件 ID，未设置时为 null")
        UUID avatarFileId,

        @Schema(description = "头像临时签名 URL；无头像或文件失效时为 null")
        String avatarUrl,

        @Schema(description = "注册时间，UTC")
        Instant createdAt,

        @Schema(description = "资料最后更新时间，UTC")
        Instant updatedAt
) {
}