package com.you.youbackend.moment.response;

import com.you.youbackend.moment.model.MomentMood;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TODO：补充中文类注释和字段 @Schema，包括内部 Image、Location 的字段说明。
 * TODO：说明图片顺序、心情枚举、各时间字段及空值、空数组的业务含义。
 */
public record MomentResponse(
        UUID id,
        String title,
        String body,
        List<Image> images,
        List<String> tags,
        MomentMood mood,
        Instant memoryTime,
        Location location,
        Instant createdAt,
        Instant updatedAt
) {
    public record Image(UUID fileId, String url) {}

    public record Location(BigDecimal latitude, BigDecimal longitude, String name, String address) {}
}
