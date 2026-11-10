package com.you.youbackend.gathering.model;

import com.you.youbackend.common.validation.UUIDv7;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * 同行经历时间线的正序分页边界。
 * @param memoryTime 上一页末项发生时间，UTC
 * @param id 上一页末项瞬间 UUIDv7
 */
@Schema(description = "同行瞬间游标，按发生时间、ID 正序")
public record GatheringMomentCursor(
        @NotNull(message = "请提供游标发生时间")
        @Schema(description = "上一页末项发生时间，UTC", format = "date-time") Instant memoryTime,
        @NotNull(message = "请提供游标瞬间 ID") @UUIDv7(checkTimestamp = false)
        @Schema(description = "上一页末项瞬间 UUIDv7", format = "uuid") UUID id
) {}
