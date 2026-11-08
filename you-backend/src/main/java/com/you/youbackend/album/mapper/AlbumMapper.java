package com.you.youbackend.album.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.album.entity.Album;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 时光主表读写，查询沿用全局逻辑删除过滤。 */
@Mapper
public interface AlbumMapper extends BaseMapper<Album> {
    /**
     * 删除专用查询，包含已软删除记录，以便校验归属并识别本人重复删除。
     * @param id 时光 ID
     * @return 完整实体，不存在返回 null
     */
    @Select("SELECT * FROM album WHERE id = #{id}")
    Album selectIncludingDeleted(@Param("id") UUID id);

    /**
     * 全量保存已校验的展示信息，显式 set 确保 null 能清空介绍和封面。
     * @param album 本人有效时光，已填充最终字段及 UTC 修改时间
     * @return 更新行数，已删除记录不会更新
     */
    default int updateContent(Album album) {
        // 仅修改展示信息和审计时间，保留所有者、整理设置及创建时间。
        return update(Wrappers.<Album>lambdaUpdate().eq(Album::getId, album.getId())
                .set(Album::getName, album.getName()).set(Album::getDescription, album.getDescription())
                .set(Album::getCoverFileId, album.getCoverFileId()).set(Album::getUpdatedAt, album.getUpdatedAt()));
    }

    /**
     * 软删除有效时光，MP 自动过滤已删除记录。
     * @param id 已校验归属的时光 ID
     * @param deletedAt 本次统一 UTC 毫秒时间
     */
    default void softDelete(UUID id, LocalDateTime deletedAt) {
        // 同时记录删除和修改时间，重复请求不覆盖原删除时间。
        update(Wrappers.<Album>lambdaUpdate().eq(Album::getId, id)
                .set(Album::getDeletedAt, deletedAt).set(Album::getUpdatedAt, deletedAt));
    }

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
