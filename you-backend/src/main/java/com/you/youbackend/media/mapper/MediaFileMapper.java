package com.you.youbackend.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.media.entity.MediaFile;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 媒体文件元数据的数据访问。
 */
@Mapper
public interface MediaFileMapper extends BaseMapper<MediaFile> {
    /**
     * 批量读取指定用户有效媒体的对象键。
     */
    default List<MediaFile> selectOwnedStorageKeys(UUID userId, Collection<UUID> fileIds,
                                                  Collection<String> mimeTypes) {
        return selectList(Wrappers.<MediaFile>lambdaQuery()
                .select(MediaFile::getId, MediaFile::getStorageKey)
                .in(MediaFile::getId, fileIds)
                .eq(MediaFile::getOwnerUserId, userId)
                .in(MediaFile::getMimeType, mimeTypes));
    }

    /**
     * 判断指定用户的有效文件记录是否存在，沿用 MyBatis-Plus 全局软删除过滤。
     *
     * @param fileId 文件 ID
     * @param userId 文件所属用户 ID
     * @return 已登记、未删除且属于该用户时返回 true，否则返回 false
     */
    default boolean existsOwnedFile(UUID fileId, UUID userId) {
        return selectCount(Wrappers.<MediaFile>lambdaQuery()
                .eq(MediaFile::getId, fileId)
                .eq(MediaFile::getOwnerUserId, userId)) > 0;
    }
}
