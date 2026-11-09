package com.you.youbackend.user.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 对其他用户展示的公开资料。
 * @param id 用户 UUIDv7
 * @param nickname 展示昵称
 * @param avatarUrl 头像临时签名 URL；无头像或文件失效时为 null
 */
@Schema(description = "用户公开资料")
public record PublicUserResponse(
        @Schema(description = "用户 UUIDv7", format = "uuid")
        UUID id,

        @Schema(description = "展示昵称")
        String nickname,

        @Schema(description = "头像临时签名 URL；无头像或文件失效时为 null", format = "uri", nullable = true)
        String avatarUrl
) {
}