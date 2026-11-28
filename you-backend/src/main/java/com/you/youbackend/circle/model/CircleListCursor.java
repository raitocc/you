package com.you.youbackend.circle.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * 我的圈子列表分页边界，按成员关系加入时间、圈子 ID 倒序。
 * @param joinedAt 上一页末项的成员关系创建时间，UTC
 * @param id 上一页末项的圈子 UUIDv7
 */
@Schema(description = "圈子列表游标，按加入时间、圈子 ID 倒序")
public record CircleListCursor(
        @NotNull(message = "请提供游标加入时间")
        @Schema(description = "上一页末项加入时间，UTC", format = "date-time")
        Instant joinedAt,

        @NotNull(message = "请提供游标圈子 ID")
        @Schema(description = "上一页末项圈子 ID", format = "uuid")
        UUID id
) {}
