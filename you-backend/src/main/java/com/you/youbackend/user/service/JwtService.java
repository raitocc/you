package com.you.youbackend.user.service;

import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.user.model.AccessTokenClaims;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * JWT 访问令牌的签发和解析，签名密钥取自配置。
 */
@Service
public class JwtService {

    private static final String ISSUER = "you";

    private final SecretKey signingKey;

    /**
     * 用配置的密钥串初始化 HMAC 签名密钥。密钥长度不足时启动即失败。
     *
     * @param secret 配置项 jwt.secret 的原始字符串
     */
    public JwtService(@Value("${jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 给已通过身份验证的用户签一个访问令牌。
     *
     * <p>纯计算，不查库、无副作用。令牌含 userId（subject）、jti 和过期时间。</p>
     *
     * @param userId    用户 ID
     * @param expiresAt 过期时间，UTC，调用方决定有效期长短
     * @return 签好名的 JWT 字符串
     */
    public String createAccessToken(UUID userId, Instant expiresAt) {
        return Jwts.builder()
                .subject(userId.toString())
                .issuer(ISSUER)
                .issuedAt(Date.from(Instant.now()))
                .id(UUID.randomUUID().toString())
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 验签并解析访问令牌，取出用户 ID、令牌编号和过期时间。
     *
     * <p>纯计算，不查库、无副作用。签名不符、过期、缺必要字段都算无效。</p>
     *
     * @param token 不含 Bearer 前缀的 JWT 字符串，调用方保证非空
     * @return 已验证的令牌信息
     * @throws BusinessException 令牌无效或已过期（401）
     */
    public AccessTokenClaims parseAccessToken(String token) {
        try {
            // 1. 验签并解析，签名不符、过期、发行方不对都会抛 JwtException
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // 2. 必要字段缺失也算无效令牌
            if (claims.getSubject() == null
                    || !StringUtils.hasText(claims.getId())
                    || claims.getExpiration() == null) {

                throw new JwtException("令牌缺少必要字段");
            }

            // 3. 拼成业务侧好用的令牌信息；subject 转不成 UUID 也按无效处理
            return new AccessTokenClaims(
                    UUID.fromString(claims.getSubject()),
                    claims.getId(),
                    claims.getExpiration().toInstant()
            );
        } catch (JwtException | IllegalArgumentException exception) {
            // 验签失败、字段缺失、UUID 非法统一转 401，具体原因不外露
            throw BusinessException.unauthorized("登录凭证无效或已过期，请重新登录", exception);
        }
    }
}
