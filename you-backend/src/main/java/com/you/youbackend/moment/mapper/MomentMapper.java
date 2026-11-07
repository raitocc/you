package com.you.youbackend.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.model.MomentFeedCursor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 访问瞬间主表。
 */
@Mapper
public interface MomentMapper extends BaseMapper<Moment> {
    /**
     * 锁定瞬间并读取作者和删除状态，用于校验重复删除。
     * TODO：结合一期删除流程评估能否简化此查询；说明刻意包含已删除记录的原因及调用事务要求。
     * TODO：目前只读取作者和删除时间却返回完整 Moment，简化查询时消除此特殊结果，或改用明确的 MomentDeletionState。
     * TODO：移除 SQL 参数中重复指定的 UuidTypeHandler，统一使用项目已注册的全局 UUID 映射。
     */
    @Select("""
            SELECT author_user_id AS authorUserId, deleted_at AS deletedAt
            FROM moment WHERE id = #{id,typeHandler=com.you.youbackend.common.mybatis.UuidTypeHandler}
            FOR UPDATE
            """)
    Moment selectDeletionState(@Param("id") UUID id);

    /**
     * 锁定有效瞬间，串行化同一瞬间的全量编辑。
     * TODO：与删除流程一起评估一期并发处理，选择简单、一致的方案；保留锁时说明其避免关联替换交叉执行的用途及事务前提。
     */
    default Moment selectForUpdate(UUID id) {
        return selectOne(Wrappers.<Moment>lambdaQuery().eq(Moment::getId, id).last("FOR UPDATE"));
    }

    /**
     * 全量更新可编辑字段，允许显式清空可空字段。
     * TODO：补充实体已完成校验与字段整理的入参前提，说明显式 set 用于将 null 写入数据库以完成字段清空。
     */
    default void updateContent(Moment moment) {
        update(Wrappers.<Moment>lambdaUpdate()
                .eq(Moment::getId, moment.getId())
                .set(Moment::getTitle, moment.getTitle())
                .set(Moment::getBody, moment.getBody())
                .set(Moment::getMood, moment.getMood())
                .set(Moment::getMemoryTime, moment.getMemoryTime())
                .set(Moment::getLatitude, moment.getLatitude())
                .set(Moment::getLongitude, moment.getLongitude())
                .set(Moment::getLocationName, moment.getLocationName())
                .set(Moment::getLocationAddress, moment.getLocationAddress())
                .set(Moment::getUpdatedAt, moment.getUpdatedAt()));
    }

    /**
     * 读取本人瞬间流中位于游标之后的卡片数据。
     * TODO：说明按发生时间、ID 双字段倒序排列；游标为空查询首屏，rowCount 为实际页大小加一，用于判断下一页。
     * TODO：明确仅查询卡片所需字段；评估使用独立查询模型，避免部分字段填充的 Moment 被误当完整实体。
     */
    default List<Moment> selectFeed(UUID userId, MomentFeedCursor cursor, int rowCount) {
        var query = Wrappers.<Moment>lambdaQuery()
                .select(Moment::getId, Moment::getTitle, Moment::getBody, Moment::getMood, Moment::getMemoryTime)
                .eq(Moment::getAuthorUserId, userId);
        // TODO：在复杂条件旁解释边界：时间更早，或时间相同且 ID 更小；该条件须与倒序排序保持一致。
        if (cursor != null) {
            query.and(boundary -> boundary.lt(Moment::getMemoryTime, cursor.memoryTime())
                    .or(sameTime -> sameTime.eq(Moment::getMemoryTime, cursor.memoryTime())
                            .lt(Moment::getId, cursor.id())));
        }
        return selectList(query.orderByDesc(Moment::getMemoryTime, Moment::getId).last("LIMIT " + rowCount));
    }
}
