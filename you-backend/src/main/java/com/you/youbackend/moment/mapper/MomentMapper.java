package com.you.youbackend.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.you.youbackend.moment.entity.Moment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访问瞬间主表。
 */
@Mapper
public interface MomentMapper extends BaseMapper<Moment> {
}
