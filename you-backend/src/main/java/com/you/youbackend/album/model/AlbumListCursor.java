package com.you.youbackend.album.model;

import com.you.youbackend.common.validation.UUIDv7;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * 本人时光列表的排序边界，按创建时间、ID 倒序；不提供跨请求固定快照。
 * @param createdAt 上一页最后一条时光的创建时间，UTC
 * @param id 同一条时光的 UUIDv7
 */
@Schema(description = "时光列表游标，后续将 nextCursor 展开为 cursor.字段名 查询参数")
public record AlbumListCursor(
        @NotNull(message = "请提供游标创建时间")
        @Schema(description = "上一页最后一条时光的创建时间", format = "date-time") Instant createdAt,
        @NotNull(message = "请提供游标时光 ID") @UUIDv7(checkTimestamp = false)
        @Schema(description = "上一页最后一条时光 UUIDv7", format = "uuid") UUID id
) {}
