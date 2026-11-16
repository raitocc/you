package com.you.youbackend.circle.model;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
public record CircleListCursor(
        // 用户加入时间
        @NotNull Instant joinedAt,
        // 圈子 ID
        @NotNull UUID id
) {}
