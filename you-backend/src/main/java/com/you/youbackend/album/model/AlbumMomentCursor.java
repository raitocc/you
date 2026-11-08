package com.you.youbackend.album.model;

import com.you.youbackend.common.validation.UUIDv7;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * 时光线的三字段正序边界；修改发生时间会改变位置，不提供跨请求固定快照。
 * @param memoryTime 上一页最后一条瞬间的发生时间，UTC
 * @param createdAt 同一条瞬间的发布时间，UTC
 * @param id 同一条瞬间的 UUIDv7
 */
@Schema(description = "时光瞬间游标，按发生时间、发布时间、ID 正序")
public record AlbumMomentCursor(
        @NotNull(message = "请提供游标发生时间")
        @Schema(description = "上一页最后一条瞬间的发生时间", format = "date-time") Instant memoryTime,
        @NotNull(message = "请提供游标发布时间")
        @Schema(description = "同一条瞬间的发布时间", format = "date-time") Instant createdAt,
        @NotNull(message = "请提供游标瞬间 ID") @UUIDv7(checkTimestamp = false)
        @Schema(description = "同一条瞬间 UUIDv7", format = "uuid") UUID id
) {}
