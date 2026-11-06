package com.you.youbackend.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.moment.entity.MomentImage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 访问瞬间与图片的关联记录。
 */
@Mapper
public interface MomentImageMapper extends BaseMapper<MomentImage> {
    /**
     * 解除瞬间的图片关联，保留历史记录以供恢复。
     */
    default void removeAssociations(UUID momentId) {
        delete(Wrappers.<MomentImage>lambdaQuery().eq(MomentImage::getMomentId, momentId));
    }

    /**
     * 新增或恢复图片关联，并写入本次提交的展示位置。
     */
    @Insert("""
            INSERT INTO moment_image (id, moment_id, file_id, sort_order, created_at, updated_at)
            VALUES (#{id}, #{momentId}, #{fileId}, #{sortOrder}, #{createdAt}, #{updatedAt}) AS incoming
            ON DUPLICATE KEY UPDATE sort_order = incoming.sort_order,
                updated_at = incoming.updated_at, deleted_at = NULL
            """)
    void saveAssociation(MomentImage image);

    /**
     * 按展示顺序读取瞬间的有效图片关联。
     */
    default List<MomentImage> selectInDisplayOrder(UUID momentId) {
        return selectList(Wrappers.<MomentImage>lambdaQuery()
                .eq(MomentImage::getMomentId, momentId)
                .orderByAsc(MomentImage::getSortOrder));
    }

    /**
     * 批量读取一页瞬间的图片关联，保留每组图片的展示顺序。
     */
    default List<MomentImage> selectFeedImages(Collection<UUID> momentIds) {
        return selectList(Wrappers.<MomentImage>lambdaQuery()
                .select(MomentImage::getMomentId, MomentImage::getFileId)
                .in(MomentImage::getMomentId, momentIds)
                .orderByAsc(MomentImage::getMomentId, MomentImage::getSortOrder));
    }
}
