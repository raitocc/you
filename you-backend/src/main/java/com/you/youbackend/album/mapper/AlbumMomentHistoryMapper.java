package com.you.youbackend.album.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.album.entity.AlbumMomentHistory;
import org.apache.ibatis.annotations.Mapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 时光瞬间历史关联读写。 */
@Mapper
public interface AlbumMomentHistoryMapper extends BaseMapper<AlbumMomentHistory> {
    /** 批量读取候选关联，用于区分新增、保留与恢复。 */
    default List<AlbumMomentHistory> selectIncludingDeleted(UUID albumId, List<UUID> momentIds) {
        // 按同一时光和本次提交的瞬间限定候选范围。
        return selectList(Wrappers.<AlbumMomentHistory>lambdaQuery()
                .eq(AlbumMomentHistory::getAlbumId, albumId).in(AlbumMomentHistory::getMomentId, momentIds));
    }

    /** 恢复仍处于移出状态的关联，保留原 ID 与创建时间。 */
    default void restoreAssociations(UUID albumId, List<UUID> momentIds, LocalDateTime savedAt) {
        // 只更新本次待恢复的历史关系，避免覆盖已有效的关系。
        update(Wrappers.<AlbumMomentHistory>lambdaUpdate().eq(AlbumMomentHistory::getAlbumId, albumId)
                .in(AlbumMomentHistory::getMomentId, momentIds).isNotNull(AlbumMomentHistory::getRemovedAt)
                .set(AlbumMomentHistory::getRemovedAt, null).set(AlbumMomentHistory::getUpdatedAt, savedAt));
    }
}
