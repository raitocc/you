package com.you.youbackend.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.moment.entity.MomentTag;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

/**
 * 访问瞬间标签记录。
 */
@Mapper
public interface MomentTagMapper extends BaseMapper<MomentTag> {
    /**
     * 软删除瞬间的有效标签关联，同步更新修改时间，保留历史记录。
     *
     * @param momentId 瞬间 ID
     * @param deletedAt 本次解除关联的 UTC 时间
     */
    default void removeAssociations(UUID momentId, LocalDateTime deletedAt) {
        update(Wrappers.<MomentTag>lambdaUpdate()
                .eq(MomentTag::getMomentId, momentId)
                .set(MomentTag::getDeletedAt, deletedAt)
                .set(MomentTag::getUpdatedAt, deletedAt));
    }

    /**
     * 为新发布的瞬间添加标签，已存在等价名称时保留首次保存的名称。
     *
     * <p>查询使用数据库与唯一索引相同的排序规则，统一判断大小写、重音等价性，
     * 避免在 Service 中通过捕获唯一键异常进行正常去重。
     * 仅用于主记录刚插入的发布事务，此时无历史软删除标签，也无其他发布请求同时写入该瞬间。</p>
     *
     * @param tag 已整理名称并填好 ID、瞬间 ID 和审计时间的标签实体
     * @return true 表示已插入，false 表示本次发布中已有等价标签
     */
    default boolean insertIfAbsent(MomentTag tag) {
        Long count = selectCount(Wrappers.<MomentTag>lambdaQuery()
                .eq(MomentTag::getMomentId, tag.getMomentId())
                .eq(MomentTag::getTagName, tag.getTagName()));
        if (count > 0) {
            return false;
        }
        insert(tag);
        return true;
    }

    /**
     * 按数据库唯一索引的比较规则查找标签，包含已删除记录。
     * 名称比较交给数据库，保证大小写、重音的判断与唯一约束一致。
     */
    @Select("""
            SELECT id, moment_id, tag_name, created_at, updated_at, deleted_at
            FROM moment_tag WHERE moment_id = #{momentId} AND tag_name = #{name}
            """)
    MomentTag selectIncludingDeletedByName(@Param("momentId") UUID momentId, @Param("name") String name);

    /** 恢复历史标签并采用本次名称，保留原 ID 和创建时间；绕过 MP 的软删除过滤。 */
    @Update("""
            UPDATE moment_tag SET deleted_at = NULL, tag_name = #{tagName}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    void restore(MomentTag tag);

    /** 读取当前有效标签，供同步时找出需要移除的记录。 */
    default List<MomentTag> selectActive(UUID momentId) {
        return selectList(Wrappers.<MomentTag>lambdaQuery().eq(MomentTag::getMomentId, momentId));
    }

    /** 软删除指定有效标签，同时更新修改时间。 */
    default void softDelete(UUID id, LocalDateTime deletedAt) {
        update(Wrappers.<MomentTag>lambdaUpdate().eq(MomentTag::getId, id)
                .set(MomentTag::getDeletedAt, deletedAt).set(MomentTag::getUpdatedAt, deletedAt));
    }

    /**
     * 读取瞬间的有效标签名称。
     * 标签无业务顺序要求，按创建时间和 ID 排序，保持读取结果稳定。
     */
    default List<String> selectNames(UUID momentId) {
        return selectList(Wrappers.<MomentTag>lambdaQuery()
                .select(MomentTag::getTagName)
                .eq(MomentTag::getMomentId, momentId)
                .orderByAsc(MomentTag::getCreatedAt, MomentTag::getId))
                .stream().map(MomentTag::getTagName).toList();
    }
}
