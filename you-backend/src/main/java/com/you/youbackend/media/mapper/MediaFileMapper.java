package com.you.youbackend.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.you.youbackend.media.entity.MediaFile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 媒体文件元数据的数据访问。
 */
@Mapper
public interface MediaFileMapper extends BaseMapper<MediaFile> {
}