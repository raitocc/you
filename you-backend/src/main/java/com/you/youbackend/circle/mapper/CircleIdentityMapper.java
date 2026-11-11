package com.you.youbackend.circle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.CircleIdentity;
import org.apache.ibatis.annotations.Mapper;

import java.util.UUID;

/** 检查圈子主键占用情况，包含解散记录。 */
@Mapper
public interface CircleIdentityMapper extends BaseMapper<CircleIdentity> {
    /** 创建前检查主键是否已被占用。 */
    default boolean isOccupied(UUID id) {
        return exists(Wrappers.<CircleIdentity>lambdaQuery().eq(CircleIdentity::getId, id));
    }
}
