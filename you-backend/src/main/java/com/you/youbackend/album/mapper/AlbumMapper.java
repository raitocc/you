package com.you.youbackend.album.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.album.entity.Album;
import org.apache.ibatis.annotations.Mapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 时光主表读写，查询沿用全局逻辑删除过滤。 */
@Mapper
public interface AlbumMapper extends BaseMapper<Album> {
    /**
     * 读取本人有效时光，按创建时间、ID 倒序，返回完整实体。
     * @param userId 当前所有者 ID
     * @param createdAt 游标 UTC 创建时间，null 表示首屏
     * @param id 与游标时间成对提供的时光 ID
     * @param rowCount 已校验页大小加一，用于判断后续页
     * @return 有序时光列表，沿用 MP 全局软删除过滤
     */
    default List<Album> selectPageRows(UUID userId, LocalDateTime createdAt, UUID id, int rowCount) {
        // 1. 限定本人时光，后续页排除上一页最后一条及其之前的排序位置。
        var query = Wrappers.<Album>lambdaQuery().eq(Album::getOwnerUserId, userId);
        if (createdAt != null) {
            query.and(boundary -> boundary.lt(Album::getCreatedAt, createdAt)
                    .or(sameTime -> sameTime.eq(Album::getCreatedAt, createdAt).lt(Album::getId, id)));
        }
        // 2. 多读取一条判断下一页，SQL 数量仅来自已校验的分页参数。
        return selectList(query.orderByDesc(Album::getCreatedAt, Album::getId).last("LIMIT " + rowCount));
    }
}
