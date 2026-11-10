package com.you.youbackend.gathering.model;

import com.you.youbackend.common.validation.UUIDv7;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * 成员关系创建时间、用户 ID 正序的分页边界。
 * @param createdAt 上一页末项的成员关系创建时间，UTC；成员重新加入保留原关系创建时间
 * @param userId 上一页末项的用户 UUIDv7
 */
@Schema(description = "成员关系创建时间、用户 ID 正序游标")
public record GatheringMemberCursor(
        @NotNull(message = "请提供游标创建时间")
        @Schema(description = "上一页末项创建时间，UTC", format = "date-time") Instant createdAt,
        @NotNull(message = "请提供游标 ID") @UUIDv7(checkTimestamp = false)
        @Schema(description = "上一页末项用户 ID", format = "uuid") UUID userId
) {}
