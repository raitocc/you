package com.you.youbackend.gathering.model;

import com.you.youbackend.common.validation.UUIDv7;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * 同行创建时间、ID 倒序的分页边界。
 * @param createdAt 上一页末项的同行创建时间，UTC
 * @param id 上一页末项的同行 UUIDv7
 */
@Schema(description = "同行创建时间、ID 倒序游标")
public record GatheringListCursor(
        @NotNull(message = "请提供游标创建时间")
        @Schema(description = "上一页末项创建时间，UTC", format = "date-time") Instant createdAt,
        @NotNull(message = "请提供游标 ID") @UUIDv7(checkTimestamp = false)
        @Schema(description = "上一页末项同行 ID", format = "uuid") UUID id
) {}
