package com.you.youbackend.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.moment.entity.MomentImage;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.util.Assert;

import java.util.*;
import java.time.LocalDateTime;

/**
 * 访问瞬间与图片的关联记录。
 */
@Mapper
public interface MomentImageMapper extends BaseMapper<MomentImage> {
    /**
     * 软删除瞬间的有效图片关联，同步更新修改时间，保留历史记录。
     *
     * @param momentId  瞬间 ID
     * @param deletedAt 本次解除关联的 UTC 时间
     */
    default void removeAssociationsByMomentId(UUID momentId, LocalDateTime deletedAt) {
        update(Wrappers.<MomentImage>lambdaUpdate()
                .eq(MomentImage::getMomentId, momentId)
                .set(MomentImage::getDeletedAt, deletedAt)
                .set(MomentImage::getUpdatedAt, deletedAt));
    }

    /**
     * 查一条瞬间的全部图片关联，含软删除的，给同步时对比历史用。
     *
     * @param momentId 瞬间 ID
     * @return 全部历史关联，按查询默认顺序
     */
    @Select("""
            SELECT id, moment_id, file_id, sort_order, created_at, updated_at, deleted_at
            FROM moment_image WHERE moment_id = #{momentId}
            """)
    List<MomentImage> selectIncludingDeletedByMomentId(@Param("momentId") UUID momentId);

    /**
     * 恢复指定历史关联，更新展示顺序，保留原 ID 和创建时间。绕过 MP 的软删除过滤。
     */
    @Update("""
            UPDATE moment_image SET deleted_at = NULL, sort_order = #{sortOrder}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    void restore(MomentImage image);

    /**
     * 更新有效关联的展示顺序及修改时间。
     */
    default void updateOrder(UUID id, int sortOrder, LocalDateTime updatedAt) {
        update(Wrappers.<MomentImage>lambdaUpdate().eq(MomentImage::getId, id)
                .set(MomentImage::getSortOrder, sortOrder).set(MomentImage::getUpdatedAt, updatedAt));
    }

    /**
     * 软删除一条有效图片关联，保留原媒体文件。
     */
    default void softDeleteById(UUID id, LocalDateTime deletedAt) {
        update(Wrappers.<MomentImage>lambdaUpdate().eq(MomentImage::getId, id)
                .set(MomentImage::getDeletedAt, deletedAt).set(MomentImage::getUpdatedAt, deletedAt));
    }

    /**
     * 按展示序号升序读取有效图片关联，软删除由 MyBatis-Plus 自动过滤。
     * 媒体文件是否仍可用由业务层批量查询确认。
     *
     * @param momentId 瞬间 ID
     * @return 有序关联列表，无关联时为空列表
     */
    default List<MomentImage> selectInDisplayOrder(UUID momentId) {
        return selectList(Wrappers.<MomentImage>lambdaQuery()
                .eq(MomentImage::getMomentId, momentId)
                .orderByAsc(MomentImage::getSortOrder));
    }

    /**
     * 批量读取一页瞬间的图片关联，保留每组图片的展示顺序。
     * 只填充 momentId、fileId，按瞬间 ID、sort_order 升序；Service 沿用顺序分组和过滤，首张有效图片作为封面。
     *
     * @param momentIds 本页实际返回的非空瞬间 ID 集合
     * @return 有序图片关联，仅供信息流文件查询和分组使用
     */
    default List<MomentImage> selectFeedImages(Collection<UUID> momentIds) {
        return selectList(Wrappers.<MomentImage>lambdaQuery()
                .select(MomentImage::getMomentId, MomentImage::getFileId)
                .in(MomentImage::getMomentId, momentIds)
                .orderByAsc(MomentImage::getMomentId, MomentImage::getSortOrder));
    }

    /**
     * 批量查多条瞬间的图片关联，每条瞬间内按展示顺序；没图的瞬间也给空列表，调用方免判 null。
     * 只投影 momentId、fileId。
     *
     * @param momentIds 瞬间 ID 集合，非空
     * @return 瞬间 ID 到有序图片列表的映射
     */
    default Map<UUID, List<MomentImage>> selectMomentsImages(Collection<UUID> momentIds) {
        // 1. 校验
        Assert.notEmpty(momentIds, "至少传入一个瞬间 ID");
        // 2. 查询所有对应的 MomentImage 实体
        List<MomentImage> momentImages = selectList(Wrappers.<MomentImage>lambdaQuery()
                .select(MomentImage::getMomentId, MomentImage::getFileId)
                .in(MomentImage::getMomentId, momentIds)
                .orderByAsc(MomentImage::getMomentId, MomentImage::getSortOrder));
        // 3. 分类整理到 Map
        Map<UUID, List<MomentImage>> momentImagesMap = new HashMap<>();
        momentIds.forEach(id -> momentImagesMap.put(id, new ArrayList<>()));
        momentImages.forEach(momentImage -> {
            momentImagesMap.get(momentImage.getMomentId()).add(momentImage);
        });
        return momentImagesMap;
    }

    /** 查一条瞬间的图片关联，委托批量版取单条。 */
    default List<MomentImage> selectMomentImages(UUID momentId) {
        return selectMomentsImages(List.of(momentId)).get(momentId);
    }
}
