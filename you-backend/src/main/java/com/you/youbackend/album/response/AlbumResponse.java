package com.you.youbackend.album.response;

import com.you.youbackend.album.model.AlbumOrganizeMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 创建和查看共用的时光信息，所有字段均返回，可空字段显式返回 null。
 * @param id 时光 UUIDv7
 * @param name 去首尾空白后的名称，1～128 字符
 * @param description 介绍，最多 5000 字符，未设置为 null
 * @param coverFileId 封面文件 ID，未设置为 null，失效时保留 ID
 * @param coverUrl 封面临时签名 URL，无封面或文件失效为 null
 * @param organizeMode 整理方式，手动创建为 manual
 * @param momentCount 当前有效的本人瞬间数量
 * @param createdAt 创建时间，UTC
 * @param updatedAt 最后修改时间，UTC
 */
@Schema(description = "时光信息")
public record AlbumResponse(
        @Schema(description = "时光 UUIDv7", format = "uuid") UUID id,
        @Schema(description = "名称，去首尾空白后 1～128 字符", minLength = 1, maxLength = 128) String name,
        @Schema(description = "介绍，未设置为 null", maxLength = 5000, nullable = true) String description,
        @Schema(description = "封面文件 ID，未设置为 null", format = "uuid", nullable = true) UUID coverFileId,
        @Schema(description = "封面签名 URL，有效期 10 分钟；无封面或文件失效为 null", format = "uri", nullable = true)
        String coverUrl,
        @Schema(description = "整理方式，手动创建为 manual") AlbumOrganizeMode organizeMode,
        @Schema(description = "当前有效的本人瞬间数量", minimum = "0") long momentCount,
        @Schema(description = "创建时间，UTC", format = "date-time") Instant createdAt,
        @Schema(description = "最后修改时间，UTC", format = "date-time") Instant updatedAt
) {}
