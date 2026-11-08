package com.you.youbackend.album.model;

import java.util.UUID;

/**
 * 本页时光的分组统计结果，仅用于查询映射，不是持久化实体。
 * @param albumId 时光 ID
 * @param momentCount 当前有效本人瞬间数量
 */
public record AlbumMomentCount(UUID albumId, long momentCount) {}
