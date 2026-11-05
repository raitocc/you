package com.you.youbackend.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.you.youbackend.user.model.AppUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户数据访问。
 */
@Mapper
public interface UserMapper extends BaseMapper<AppUser> {
}