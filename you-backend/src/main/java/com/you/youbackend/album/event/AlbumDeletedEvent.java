package com.you.youbackend.album.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 事件：一个时光已删除
 * 发布入口：AlbumService.deleteAlbum()
 * @param albumId 时光 ID
 * @param deletedAt 时光删除时间
 */
public record AlbumDeletedEvent(
        UUID albumId,
        LocalDateTime deletedAt
) {
}