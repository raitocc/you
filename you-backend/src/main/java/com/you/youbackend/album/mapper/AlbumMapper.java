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
     * 关联调整后仅刷新时光修改时间，保留其他字段，沿用软删除过滤。
     * @param id 已校验归属的有效时光 ID
     * @param updatedAt 本次统一 UTC 毫秒时间
     * @return 更新行数
     */
    default int touch(UUID id, LocalDateTime updatedAt) {
        // 关联变化只影响修改时间，不覆盖名称、介绍或封面。
        return update(Wrappers.<Album>lambdaUpdate().eq(Album::getId, id)
                .set(Album::getUpdatedAt, updatedAt));
    }

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
     * 读取本人有效时光首屏，按创建时间、ID 倒序。
     * @param userId 当前所有者 ID
     * @param rowCount 已校验页大小加一，用于判断下一页
     * @return 有序的完整时光实体列表
     */
    default List<Album> selectFirstPage(UUID userId, int rowCount) {
        // 首屏只限定所有者，按统一排序读取页大小加一条。
        return selectList(Wrappers.<Album>lambdaQuery()
                .eq(Album::getOwnerUserId, userId)
                .orderByDesc(Album::getCreatedAt, Album::getId)
                .last("LIMIT " + rowCount));
    }

    /**
     * 读取本人有效时光后续页，排除游标记录及其之前的位置。
     * @param userId 当前所有者 ID
     * @param createdAt 游标创建时间，非空，UTC 毫秒精度
     * @param id 与创建时间成对提供的游标 ID
     * @param rowCount 已校验页大小加一
     * @return 按创建时间、ID 倒序排列的完整时光实体列表
     */
    default List<Album> selectNextPage(UUID userId, LocalDateTime createdAt, UUID id, int rowCount) {
        // 后续页读取更早的创建时间，或相同时间下更小的 ID；排序与首屏一致。
        return selectList(Wrappers.<Album>lambdaQuery()
                .eq(Album::getOwnerUserId, userId)
                .and(boundary -> boundary.lt(Album::getCreatedAt, createdAt)
                        .or(sameTime -> sameTime.eq(Album::getCreatedAt, createdAt).lt(Album::getId, id)))
                .orderByDesc(Album::getCreatedAt, Album::getId)
                .last("LIMIT " + rowCount));
    }
}
