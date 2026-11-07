package com.you.youbackend.album.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.you.youbackend.album.entity.Album;
import org.apache.ibatis.annotations.Mapper;

/** 时光主表读写，查询沿用全局逻辑删除过滤。 */
@Mapper
public interface AlbumMapper extends BaseMapper<Album> {
}
