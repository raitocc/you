package com.you.youbackend.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.moment.entity.MomentTag;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.UUID;

/**
 * 访问瞬间标签记录。
 */
@Mapper
public interface MomentTagMapper extends BaseMapper<MomentTag> {
    /**
     * 移除当前标签关联，保留历史记录以供恢复。
     */
    default void removeAssociations(UUID momentId) {
        delete(Wrappers.<MomentTag>lambdaQuery().eq(MomentTag::getMomentId, momentId));
    }

    /**
     * 新增或恢复标签，同一次提交的等价标签保留首次填写的名称。
     */
    @Insert("""
            INSERT INTO moment_tag (id, moment_id, tag_name, created_at, updated_at)
            VALUES (#{id}, #{momentId}, #{tagName}, #{createdAt}, #{updatedAt}) AS incoming
            ON DUPLICATE KEY UPDATE tag_name = IF(moment_tag.deleted_at IS NULL, moment_tag.tag_name, incoming.tag_name),
                updated_at = IF(moment_tag.deleted_at IS NULL, moment_tag.updated_at, incoming.updated_at),
                deleted_at = NULL
            """)
    void saveAssociation(MomentTag tag);

    /**
     * 读取瞬间的有效标签名称。
     */
    default List<String> selectNames(UUID momentId) {
        return selectList(Wrappers.<MomentTag>lambdaQuery()
                .select(MomentTag::getTagName)
                .eq(MomentTag::getMomentId, momentId)
                .orderByAsc(MomentTag::getCreatedAt, MomentTag::getId))
                .stream().map(MomentTag::getTagName).toList();
    }
}
