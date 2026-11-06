package com.you.youbackend.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.moment.entity.MomentImage;
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
