package com.you.youbackend.moment.response;

import com.you.youbackend.moment.model.MomentMood;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
