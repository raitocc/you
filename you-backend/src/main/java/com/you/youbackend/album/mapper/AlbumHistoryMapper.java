package com.you.youbackend.album.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.you.youbackend.album.entity.AlbumHistory;
import org.apache.ibatis.annotations.Mapper;

/** 删除入口读取时光归属与删除状态，包含历史记录。 */
@Mapper
public interface AlbumHistoryMapper extends BaseMapper<AlbumHistory> {}
