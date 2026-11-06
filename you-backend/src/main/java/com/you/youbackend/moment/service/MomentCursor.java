package com.you.youbackend.moment.service;

import com.you.youbackend.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/** 签发和校验与当前用户、Tab 绑定的信息流游标。 */
@Component
class MomentCursor {

    record Position(LocalDateTime memoryTime, UUID id) {}

    private final SecretKeySpec key;

    MomentCursor(@Value("${jwt.secret}") String secret) {
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    String encode(UUID userId, String tab, Position position) {
        String payload = position.memoryTime() + "|" + position.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(userId, tab, payload));
    }

    Position decode(UUID userId, String tab, String cursor) {
        try {
            if (cursor.length() > 256) {
                throw new IllegalArgumentException();
            }
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            String payload = new String(decoder.decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(sign(userId, tab, payload), decoder.decode(parts[1]))) {
                throw new IllegalArgumentException();
            }
            String[] position = payload.split("\\|", -1);
            return new Position(LocalDateTime.parse(position[0]), UUID.fromString(position[1]));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "分页游标无效");
        }
    }

    private byte[] sign(UUID userId, String tab, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(("moment-feed:v1|" + userId + "|" + tab + "|" + payload)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法签发瞬间分页游标", exception);
        }
    }
}
