package com.you.youbackend.user.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 用户登录结果。
 */
@Schema(description = "用户登录结果")
public record LoginResponse(

        @Schema(description = "JWT 访问令牌")
        String accessToken,

        @Schema(
                description = "令牌类型，固定为 Bearer",
                example = "Bearer")
        String tokenType,

        @Schema(description = "过期时间，UTC")
        Instant expiresAt,

        @Schema(description = "本人资料")
        UserResponse user
) {
}