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
}
