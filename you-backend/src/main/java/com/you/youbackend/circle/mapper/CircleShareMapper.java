package com.you.youbackend.circle.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.CircleShare;
import com.you.youbackend.common.enumeration.ObjectType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.executor.BatchExecutorException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 圈子分享读写；“有效”指未撤回，撤回即软删，历史记录可恢复。 */
@Mapper
public interface CircleShareMapper extends BaseMapper<CircleShare> {
    /**
     * 查指定原内容在目标圈子里的分享历史，包含已撤回的记录（自定义 SQL 语义，绕过 MP 的逻辑删除过滤）。
     * @param objectType 原内容类型
     * @param objectId 原内容 ID
     * @param circleIds 目标圈子 ID 集合
     * @return 每个圈子里该内容的分享记录（含撤回历史）
     */
    default List<CircleShare> selectIncludingDeletedByObjectId(ObjectType objectType, UUID objectId,
                                                              Collection<UUID> circleIds) {
        return selectList(Wrappers.<CircleShare>lambdaQuery().eq(CircleShare::getObjectType, objectType)
                .eq(CircleShare::getObjectId, objectId).in(CircleShare::getCircleId, circleIds));
    }

    /**
     * 按圈子和分享 ID 查分享记录，包含已撤回的记录。
     * @param circleId 圈子 ID
     * @param shareId 分享 ID
     * @return 分享记录（含撤回历史），不存在时为 null
     */
    default CircleShare selectIncludingDeleted(UUID circleId, UUID shareId) {
        return selectOne(Wrappers.<CircleShare>lambdaQuery().eq(CircleShare::getCircleId, circleId)
                .eq(CircleShare::getId, shareId));
    }

    /**
     * 批量新增分享，唯一键冲突由调用方的事务回滚并翻译为业务异常。
     * @param rows 待插入的分享记录，ID 与审计时间已填充
     * @return 插入条数
     */
    default int insertShares(List<CircleShare> rows) {
        try {
            insert(rows);
            return rows.size();
        } catch (PersistenceException failure) {
            // MP 批量执行直接抛出 MyBatis 异常，按单条 Mapper 写入的约定翻译数据库错误。
            if (failure.getCause() instanceof BatchExecutorException batch) {
                var translated = new SQLErrorCodeSQLExceptionTranslator("MySQL")
                        .translate("批量写入圈子分享", null, batch.getBatchUpdateException());
                if (translated != null) throw translated;
            }
            throw failure;
        }
    }

    /**
     * 恢复仍处于撤回状态的分享，并刷新分享者和分享时间；仍有效的分享不改动。
     * @param ids 待恢复的分享 ID 集合
     * @param userId 本次分享者 ID
     * @param now 恢复时间，UTC，写入 sharedAt 和 updatedAt
     * @return 实际恢复的条数，小于待恢复数说明并发下状态已变化
     */
    default int restoreShares(List<UUID> ids, UUID userId, Instant now) {
        return update(Wrappers.<CircleShare>lambdaUpdate().in(CircleShare::getId, ids)
                .isNotNull(CircleShare::getWithdrawnAt).set(CircleShare::getWithdrawnAt, null)
                .set(CircleShare::getSharerUserId, userId).set(CircleShare::getSharedAt, now)
                .set(CircleShare::getUpdatedAt, now));
    }

    /**
     * 撤回一条仍有效的分享；已撤回的保持原撤回时间。
     * @param id 分享 ID
     * @param now 撤回时间，UTC
     * @return 实际撤回的行数
     */
    default int withdraw(UUID id, Instant now) {
        return update(withdrawal(now).eq(CircleShare::getId, id));
    }

    /**
     * 按分享时间、分享 ID 倒序读取圈内分享候选，给分享流翻页用。
     * 只含瞬间和时光两类；候选的分享者与原内容有效性由调用方批量过滤，因此按批次推进游标。
     * @param circleId 圈子 ID
     * @param sharedAt 游标分享时间，UTC；null 表示首屏
     * @param id 游标分享 ID，与 sharedAt 成对提供
     * @param limit 本批最大行数
     * @return 倒序候选记录，已撤回的由逻辑删除过滤
     */
    default List<CircleShare> selectPageAfterCursorByCircleId(UUID circleId, Instant sharedAt, UUID id, int limit) {
        return selectList(Wrappers.<CircleShare>lambdaQuery().eq(CircleShare::getCircleId, circleId)
                .isNull(CircleShare::getWithdrawnAt)
                .in(CircleShare::getObjectType, ObjectType.moment, ObjectType.album)
                .and(sharedAt != null, q -> q.lt(CircleShare::getSharedAt, sharedAt)
                        .or(tie -> tie.eq(CircleShare::getSharedAt, sharedAt).lt(CircleShare::getId, id)))
                .orderByDesc(CircleShare::getSharedAt, CircleShare::getId).last("LIMIT " + limit));
    }

    /**
     * 撤回指定成员在这个圈子里的全部有效分享，原内容保留。
     * @param circleId 圈子 ID
     * @param userId 分享者 ID
     * @param now 撤回时间，UTC
     * @return 实际撤回的条数
     */
    default int withdrawMemberShares(UUID circleId, UUID userId, Instant now) {
        return update(withdrawal(now)
                .eq(CircleShare::getCircleId, circleId)
                .eq(CircleShare::getSharerUserId, userId));
    }

    /**
     * 撤回这个圈子里的全部有效分享，原内容保留。
     * @param circleId 圈子 ID
     * @param now 撤回时间，UTC
     * @return 实际撤回的条数
     */
    default int withdrawCircleShares(UUID circleId, Instant now) {
        return update(withdrawal(now).eq(CircleShare::getCircleId, circleId));
    }

    private static LambdaUpdateWrapper<CircleShare> withdrawal(Instant now) {
        // 只撤回仍有效的分享，已撤回的保留原撤回时间。
        return Wrappers.<CircleShare>lambdaUpdate()
                .isNull(CircleShare::getWithdrawnAt)
                .set(CircleShare::getWithdrawnAt, now)
                .set(CircleShare::getUpdatedAt, now);
    }
}
