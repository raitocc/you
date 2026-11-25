package com.you.youbackend.user.service;

import com.you.youbackend.user.model.AccessTokenClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 用 Redis 维护已撤销访问令牌的黑名单，退出登录后旧令牌立即失效。
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "you:auth:revoked:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 查这个令牌有没有被撤销过。
     *
     * <p>只读查询，无副作用。</p>
     *
     * @param jti 令牌唯一编号
     * @return 在黑名单里返回 true
     */
    public boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(KEY_PREFIX + jti)
        );
    }

    /**
     * 把令牌拉黑，保留到它自然过期为止；已过期的令牌直接跳过（没必要再记）。
     *
     * <p>流程：算令牌剩余有效期 → 已过期直接返回 → 写 Redis 黑名单（键按 jti）。
     * 副作用：往 Redis 写一条带过期时间的黑名单记录，重复撤销按同键覆盖，幂等。</p>
     *
     * @param token 已通过验证的令牌信息，过期时间用它的 expiresAt
     */
    public void revoke(AccessTokenClaims token) {
        // 1. 用过期时间减当前时间算剩余有效期
        long remainingMillis = token.expiresAt().toEpochMilli()
                - System.currentTimeMillis();
        // 2. 已过期的令牌直接跳过，没必要再记
        if (remainingMillis <= 0) {
            return;
        }
        // 3. 写 Redis 黑名单，键按 jti，过期时间和令牌一致，到点自动清
        redisTemplate.opsForValue().set(
                KEY_PREFIX + token.jti(),
                "1",
                Duration.ofMillis(remainingMillis)
        );

        log.info("用户退出登录，userId={}，jti={}",
                token.userId(), token.jti());
    }
}