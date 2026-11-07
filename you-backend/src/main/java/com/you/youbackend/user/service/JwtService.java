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
 * JWT 访问令牌签发。
 */
@Service
public class JwtService {

    private static final String ISSUER = "you";

    private final SecretKey signingKey;

    /**
     * 根据配置的密钥初始化 JWT 签名密钥。
     */
    public JwtService(@Value("${jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 为已通过身份验证的用户签发访问令牌。
     *
     * @param userId    用户 ID
     * @param expiresAt 过期时间
     * @return JWT 字符串
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
     * 验证访问令牌并读取用户身份。
     *
     * @param token 不含 Bearer 前缀的 JWT 字符串
     * @return 用户 ID
     */
    public UUID parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (claims.getSubject() == null || claims.getExpiration() == null) {
                throw new JwtException("令牌缺少用户身份或过期时间");
            }

            return UUID.fromString(claims.getSubject());
        } catch (JwtException | IllegalArgumentException exception) {
            throw BusinessException.unauthorized("登录凭证无效或已过期，请重新登录", exception);
        }
    }

    /**
     * 验证访问令牌，读取用户身份、令牌编号及过期时间。
     *
     * @param token 不含 Bearer 前缀的 JWT 字符串
     * @return 已验证的令牌信息
     */
    public AccessTokenClaims parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (claims.getSubject() == null
                    || !StringUtils.hasText(claims.getId())
                    || claims.getExpiration() == null) {

                throw new JwtException("令牌缺少必要字段");
            }

            return new AccessTokenClaims(
                    UUID.fromString(claims.getSubject()),
                    claims.getId(),
                    claims.getExpiration().toInstant()
            );
        } catch (JwtException | IllegalArgumentException exception) {
            throw BusinessException.unauthorized("登录凭证无效或已过期，请重新登录", exception);
        }
    }
}
