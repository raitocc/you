package com.you.youbackend.album.response;

import com.you.youbackend.album.model.AlbumOrganizeMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * 一条时光的概要信息
 * @param id 时光 ID
 * @param name 时光名称
 * @param coverUrl 时光封面签名 URL
 * @param momentCount 时光当前有效的瞬间数量
 * @param organizeMode 时光整理方式：【manual 或 ai】
 * @param createdAt 时光创建时间
 * @param updatedAt 时光最后修改时间
 */
@Schema(description = "时光概要")
public record AlbumSummaryResponse(
        @Schema(description = "时光 ID", format = "uuid")
        UUID id,

        @Schema(description = "时光名称", minLength = 1, maxLength = 128)
        String name,

        @Schema(description = "时光封面签名 URL", format = "uri", nullable = true)
        String coverUrl,

        @Schema(description = "时光当前有效的瞬间数量", minimum = "0")
        long momentCount,

        @Schema(description = "时光整理方式")
        AlbumOrganizeMode organizeMode,

        @Schema(description = "时光创建时间", format = "date-time")
        Instant createdAt,

        @Schema(description = "时光最后修改时间", format = "date-time")
        Instant updatedAt
) {}
