package com.you.youbackend.album.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.album.entity.AlbumMoment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.executor.BatchExecutorException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 时光瞬间有效关联读写，沿用全局逻辑删除。 */
@Mapper
public interface AlbumMomentMapper extends BaseMapper<AlbumMoment> {
    /**
     * 移出当前时光内指定的有效关联，未加入及已移出项跳过。
     * @param albumId 本人有效时光 ID
     * @param momentIds 非空且已去重的瞬间 ID
     * @param savedAt 本次统一 UTC 时间
     */
    default void removeSelectedAssociations(UUID albumId, List<UUID> momentIds, LocalDateTime savedAt) {
        // 仅更新当前时光中的有效关联，保留原瞬间及其他时光关系。
        update(Wrappers.<AlbumMoment>lambdaUpdate().eq(AlbumMoment::getAlbumId, albumId)
                .in(AlbumMoment::getMomentId, momentIds)
                .set(AlbumMoment::getDeletedAt, savedAt).set(AlbumMoment::getUpdatedAt, savedAt));
    }

    /**
     * 批量解除指定时光的全部有效关联，原瞬间和其他时光的关联保留。
     * @param albumId 已校验归属的时光 ID
     * @param deletedAt 与主记录一致的 UTC 毫秒时间
     */
    default void removeAssociations(UUID albumId, LocalDateTime deletedAt) {
        // MP 过滤已删除关联，保留历史移出时间，不访问原瞬间和媒体表。
        update(Wrappers.<AlbumMoment>lambdaUpdate().eq(AlbumMoment::getAlbumId, albumId)
                .set(AlbumMoment::getDeletedAt, deletedAt).set(AlbumMoment::getUpdatedAt, deletedAt));
    }

    /** 批量插入新关联，调用方事务负责整体提交或回滚。 */
    default void insertAssociations(List<AlbumMoment> associations) {
        try {
            insert(associations);
        } catch (PersistenceException failure) {
            // MP 批处理直接抛出 MyBatis 异常，沿用单条写入的 Spring 异常转换。
            if (failure.getCause() instanceof BatchExecutorException batch) {
                var translated = new SQLErrorCodeSQLExceptionTranslator("MySQL")
                        .translate("批量写入时光关联", null, batch.getBatchUpdateException());
                if (translated != null) throw translated;
            }
            throw failure;
        }
    }

    /** 读取目标时光的有效关联，仅投影用于查询共享瞬间的两个 ID。 */
    default List<AlbumMoment> selectActiveLinks(List<UUID> albumIds) {
        return selectList(Wrappers.<AlbumMoment>lambdaQuery()
                .select(AlbumMoment::getAlbumId, AlbumMoment::getMomentId)
                .in(AlbumMoment::getAlbumId, albumIds));
    }
}
