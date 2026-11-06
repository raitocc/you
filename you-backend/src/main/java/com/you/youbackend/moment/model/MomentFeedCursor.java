package com.you.youbackend.moment.model;

import com.you.youbackend.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

public record MomentFeedCursor(LocalDateTime memoryTime, UUID id) {

    /**
     * 解析客户端原样传回的分页边界。
     */
    public static MomentFeedCursor decode(String cursor) {
        try {
            if (cursor.length() > 100) {
                throw new IllegalArgumentException();
            }
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            String[] fields = value.split("\\|", -1);
            if (fields.length != 2) {
                throw new IllegalArgumentException();
            }
            LocalDateTime time = LocalDateTime.parse(fields[0]);
            UUID id = UUID.fromString(fields[1]);
            if (time.getYear() < 1000 || time.getYear() > 9999
                    || time.getNano() % 1_000_000 != 0 || !id.toString().equals(fields[1])) {
                throw new IllegalArgumentException();
            }
            return new MomentFeedCursor(time, id);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "分页游标无效");
        }
    }

    /**
     * 将本页最后一条记录的排序键编码为游标。
     */
    public String encode() {
        String value = memoryTime + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }
}
