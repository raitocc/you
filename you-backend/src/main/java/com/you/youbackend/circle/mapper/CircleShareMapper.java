package com.you.youbackend.circle.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.CircleShare;
import com.you.youbackend.circle.model.CircleShareObjectType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.executor.BatchExecutorException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import java.time.LocalDateTime;
import java.util.*;

/** 圈子分享读写，按方法用途显式限定撤回状态。 */
@Mapper
public interface CircleShareMapper extends BaseMapper<CircleShare> {
    /** 读取指定内容在目标圈子里的分享历史。 */
    default List<CircleShare> selectHistory(CircleShareObjectType type, UUID objectId, Collection<UUID> ids) {
        return selectList(Wrappers.<CircleShare>lambdaQuery().eq(CircleShare::getObjectType, type)
                .eq(CircleShare::getObjectId, objectId).in(CircleShare::getCircleId, ids));
    }

    /** 按圈子及分享 ID 查询，包含已撤回记录。 */
    default CircleShare selectHistoryById(UUID circleId, UUID shareId) {
        return selectOne(Wrappers.<CircleShare>lambdaQuery().eq(CircleShare::getCircleId, circleId)
                .eq(CircleShare::getId, shareId));
    }

    /** 批量新增分享，调用方事务处理唯一键冲突。 */
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

    /** 恢复仍处于撤回状态的分享，并更新分享者和时间。 */
    default int restoreShares(List<UUID> ids, UUID userId, LocalDateTime now) {
        return update(Wrappers.<CircleShare>lambdaUpdate().in(CircleShare::getId, ids)
                .isNotNull(CircleShare::getWithdrawnAt).set(CircleShare::getWithdrawnAt, null)
                .set(CircleShare::getSharerUserId, userId).set(CircleShare::getSharedAt, now)
                .set(CircleShare::getUpdatedAt, now));
    }

    /** 只撤回当前仍有效的分享。 */
    default int withdraw(UUID id, LocalDateTime now) {
        return update(withdrawal(now).eq(CircleShare::getId, id));
    }

    /** 读取未撤回的瞬间或时光分享候选，按时间和 ID 倒序。 */
    default List<CircleShare> selectPageCandidates(UUID circleId, LocalDateTime time, UUID id, int limit) {
        return selectList(Wrappers.<CircleShare>lambdaQuery().eq(CircleShare::getCircleId, circleId)
                .isNull(CircleShare::getWithdrawnAt)
                .in(CircleShare::getObjectType, CircleShareObjectType.moment, CircleShareObjectType.album)
                .and(time != null, q -> q.lt(CircleShare::getSharedAt, time)
                        .or(tie -> tie.eq(CircleShare::getSharedAt, time).lt(CircleShare::getId, id)))
                .orderByDesc(CircleShare::getSharedAt, CircleShare::getId).last("LIMIT " + limit));
    }

    /**
     * 撤回指定成员在这个圈子里的全部有效分享。
     */
    default int invalidateMemberShares(UUID circleId, UUID userId, LocalDateTime now) {
        return update(withdrawal(now)
                .eq(CircleShare::getCircleId, circleId)
                .eq(CircleShare::getSharerUserId, userId));
    }
    /**
     * 撤回这个圈子里的全部有效分享。
     */
    default int invalidateCircleShares(UUID circleId, LocalDateTime now) {
        return update(withdrawal(now).eq(CircleShare::getCircleId, circleId));
    }

    private static LambdaUpdateWrapper<CircleShare> withdrawal(LocalDateTime now) {
        return Wrappers.<CircleShare>lambdaUpdate()
                .isNull(CircleShare::getWithdrawnAt)
                .set(CircleShare::getWithdrawnAt, now)
                .set(CircleShare::getUpdatedAt, now);
    }
}
