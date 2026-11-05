package com.you.youbackend.user.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 已通过验证的访问令牌信息，供服务端内部使用。
 *
 * @param userId    用户 ID
 * @param jti       令牌唯一编号
 * @param expiresAt 令牌过期时间
 */
public record AccessTokenClaims(
        UUID userId,
        String jti,
        Instant expiresAt
) {
}