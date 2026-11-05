package com.you.youbackend.user.service;

import com.you.youbackend.user.model.AccessTokenClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 保存和查询已撤销的访问令牌。
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "you:auth:revoked:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 查询令牌是否已被撤销。
     */
    public boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(KEY_PREFIX + jti)
        );
    }

    /**
     * 撤销当前令牌，黑名单记录保留至令牌过期。
     */
    public void revoke(AccessTokenClaims token) {
        long remainingMillis = token.expiresAt().toEpochMilli()
                - System.currentTimeMillis();

        if (remainingMillis <= 0) {
            return;
        }

        redisTemplate.opsForValue().set(
                KEY_PREFIX + token.jti(),
                "1",
                Duration.ofMillis(remainingMillis)
        );

        log.info("用户退出登录，userId={}，jti={}",
                token.userId(), token.jti());
    }
}