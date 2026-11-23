package com.you.youbackend.album.response;

import com.you.youbackend.album.model.AlbumOrganizeMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条时光的详情信息
 * @param id 时光 ID
 * @param name 时光名称
 * @param description 时光介绍
 * @param coverFileId 时光封面 ID
 * @param coverUrl 时光封面签名 URL
 * @param organizeMode 时光整理方式
 * @param momentCount 时光当前有效的瞬间数量
 * @param createdAt 时光创建时间
 * @param updatedAt 时光最后修改时间
 */
@Schema(description = "时光详情")
@Builder
public record AlbumDetailedResponse(
        @Schema(description = "时光 ID", format = "uuid")
        UUID id,

        @Schema(description = "时光名称", minLength = 1, maxLength = 128)
        String name,

        @Schema(description = "时光介绍", maxLength = 5000, nullable = true)
        String description,

        @Schema(description = "时光封面 ID", format = "uuid", nullable = true)
        UUID coverFileId,

        @Schema(description = "时光封面签名 URL", format = "uri", nullable = true)
        String coverUrl,

        @Schema(description = "时光整理方式")
        AlbumOrganizeMode organizeMode,

        @Schema(description = "时光当前有效的瞬间数量", minimum = "0")
        long momentCount,

        @Schema(description = "时光创建时间", format = "date-time")
        Instant createdAt,

        @Schema(description = "时光最后修改时间", format = "date-time")
        Instant updatedAt
) {}
