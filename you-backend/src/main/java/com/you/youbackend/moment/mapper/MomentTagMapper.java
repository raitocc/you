package com.you.youbackend.moment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.moment.entity.MomentTag;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.UUID;

/**
 * 访问瞬间标签记录。
 */
@Mapper
public interface MomentTagMapper extends BaseMapper<MomentTag> {
    /**
     * 读取瞬间的有效标签名称。
     */
    default List<String> selectNames(UUID momentId) {
        return selectList(Wrappers.<MomentTag>lambdaQuery()
                .select(MomentTag::getTagName)
                .eq(MomentTag::getMomentId, momentId)
                .orderByAsc(MomentTag::getCreatedAt, MomentTag::getId))
                .stream().map(MomentTag::getTagName).toList();
    }
}
