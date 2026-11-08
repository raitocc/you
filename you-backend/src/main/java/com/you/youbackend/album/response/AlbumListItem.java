package com.you.youbackend.album.response;

import com.you.youbackend.album.model.AlbumOrganizeMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * 本人时光列表卡片，空时光同样返回。
 * @param id 时光 UUIDv7
 * @param name 去首尾空白后的名称，1～128 字符
 * @param coverUrl 有效期 10 分钟的封面签名 URL，无封面或失效时为 null
 * @param momentCount 当前有效的本人瞬间数量，空时光为 0
 * @param organizeMode 整理方式 manual 或 ai
 * @param createdAt 创建时间，UTC
 * @param updatedAt 最后修改时间，UTC
 */
@Schema(description = "本人时光列表卡片")
public record AlbumListItem(
        @Schema(description = "时光 UUIDv7", format = "uuid") UUID id,
        @Schema(description = "时光名称", minLength = 1, maxLength = 128) String name,
        @Schema(description = "封面签名 URL，无封面或失效时为 null", format = "uri", nullable = true) String coverUrl,
        @Schema(description = "当前有效的本人瞬间数量", minimum = "0") long momentCount,
        @Schema(description = "整理方式") AlbumOrganizeMode organizeMode,
        @Schema(description = "创建时间，UTC", format = "date-time") Instant createdAt,
        @Schema(description = "最后修改时间，UTC", format = "date-time") Instant updatedAt
) {}
