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
     * TODO：软删除关联时同步更新 updated_at，与瞬间及图片关联采用一致的审计字段处理方式。
     */
    default void removeAssociations(UUID momentId) {
        delete(Wrappers.<MomentTag>lambdaQuery().eq(MomentTag::getMomentId, momentId));
    }

    /**
     * 新增或恢复标签，同一次提交的等价标签保留首次填写的名称。
     * TODO：统一创建和修改的标签等价、去重及保留名称规则，再简化保存实现，避免规则分散在 Java 去重、异常捕获和 SQL IF 中。
     * TODO：解释当前 IF：恢复旧的已删除关联时使用本次名称；命中已有效关联时保留首次名称，处理本次提交的等价重复标签。
     * TODO：明确唯一键为 moment_id、tag_name，恢复旧关联会保留旧 ID 和创建时间；评估此保留策略是否确有业务需求。
     * TODO：优先选择能清楚表达规则的简单实现，MP 与 SQL 按可读性取舍；保留 SQL 时逐步说明条件更新及清空删除时间的用途。
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
     * TODO：明确标签是否有顺序要求；当前按历史创建时间和 ID 排序，恢复旧关联后不保证与本次提交顺序一致。
     */
    default List<String> selectNames(UUID momentId) {
        return selectList(Wrappers.<MomentTag>lambdaQuery()
                .select(MomentTag::getTagName)
                .eq(MomentTag::getMomentId, momentId)
                .orderByAsc(MomentTag::getCreatedAt, MomentTag::getId))
                .stream().map(MomentTag::getTagName).toList();
    }
}
