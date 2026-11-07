package com.you.youbackend.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.moment.entity.Moment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
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
     * 按发生时间、ID 双字段倒序排列，发生时间相同的记录按唯一 ID 确定顺序。
     *
     * @param userId 当前用户 ID，仅查询本人有效瞬间
     * @param cursorTime 游标的 UTC 入库时间，null 表示首屏
     * @param cursorId 游标记录 ID，由 Service 与 cursorTime 成对传入
     * @param rowCount 实际页大小加一，额外一条用于判断下一页
     * @return 仅填充卡片查询所需字段的瞬间列表
     * TODO：明确仅查询卡片所需字段；评估使用独立查询模型，避免部分字段填充的 Moment 被误当完整实体。
     */
    default List<Moment> selectFeed(UUID userId, LocalDateTime cursorTime, UUID cursorId, int rowCount) {
        var query = Wrappers.<Moment>lambdaQuery()
                .select(Moment::getId, Moment::getTitle, Moment::getBody, Moment::getMood, Moment::getMemoryTime)
                .eq(Moment::getAuthorUserId, userId);
        // 倒序分页仅查询更早的时间，或相同时间下更小的 ID，排除上一页边界及其之前的记录。
        if (cursorTime != null) {
            query.and(boundary -> boundary.lt(Moment::getMemoryTime, cursorTime)
                    .or(sameTime -> sameTime.eq(Moment::getMemoryTime, cursorTime)
                            .lt(Moment::getId, cursorId)));
        }
        return selectList(query.orderByDesc(Moment::getMemoryTime, Moment::getId).last("LIMIT " + rowCount));
    }
}
