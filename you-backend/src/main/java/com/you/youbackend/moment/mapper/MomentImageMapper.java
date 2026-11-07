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
     * TODO：软删除关联时同步更新 updated_at，明确仅解除关联、保留原媒体文件的行为。
     */
    default void removeAssociations(UUID momentId) {
        delete(Wrappers.<MomentImage>lambdaQuery().eq(MomentImage::getMomentId, momentId));
    }

    /**
     * 新增或恢复图片关联，并写入本次提交的展示位置。
     * TODO：先确认保留历史关联 ID 的业务必要性，再评估 MP 查询、新增和更新能否更简单地表达；保留 SQL 时补全意图与执行步骤。
     * TODO：明确唯一键为 moment_id、file_id，命中旧记录时保留旧 ID 和创建时间，更新顺序、修改时间并清空删除时间，仅恢复关联。
     * TODO：将方法名调整为能表达新增或恢复关联的名称，并说明提交顺序如何对应 sort_order。
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
     * TODO：说明返回实体只填充 momentId、fileId，排序使用 sort_order；调用方应按此顺序分组、筛选，选取首张有效图片作为封面。
     */
    default List<MomentImage> selectFeedImages(Collection<UUID> momentIds) {
        return selectList(Wrappers.<MomentImage>lambdaQuery()
                .select(MomentImage::getMomentId, MomentImage::getFileId)
                .in(MomentImage::getMomentId, momentIds)
                .orderByAsc(MomentImage::getMomentId, MomentImage::getSortOrder));
    }
}
