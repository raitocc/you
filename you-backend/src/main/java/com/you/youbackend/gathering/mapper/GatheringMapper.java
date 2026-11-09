package com.you.youbackend.gathering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.gathering.entity.Gathering;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 同行主表读写，复用全局 UUID 映射与软删除配置。 */
@Mapper
public interface GatheringMapper extends BaseMapper<Gathering> {
    /**
     * 批量读取仍有效且开放邀请的同行，用于附近候选过滤。
     * @param ids 非空且去重的候选同行 ID
     * @return 完整同行实体，进行中和已结束采用相同加入规则
     */
    default List<Gathering> selectJoinableByIds(Collection<UUID> ids) {
        // 一次过滤附近候选，已解散记录由全局逻辑删除配置过滤。
        return selectList(Wrappers.<Gathering>lambdaQuery()
                .in(Gathering::getId, ids).eq(Gathering::getInviteEnabled, true));
    }

    /**
     * 根据长期邀请码读取未解散的同行，邀请开关由业务层判断。
     * @param inviteCode 已通过非空白与长度校验的邀请码
     * @return 完整同行实体，不存在或已解散时为 null
     */
    default Gathering selectByInviteCode(String inviteCode) {
        // 唯一邀请码定位同行，逻辑删除过滤由 MyBatis-Plus 自动补充。
        return selectOne(Wrappers.<Gathering>lambdaQuery().eq(Gathering::getInviteCode, inviteCode));
    }
}