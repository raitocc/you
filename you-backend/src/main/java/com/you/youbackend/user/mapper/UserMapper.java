package com.you.youbackend.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.user.model.AppUser;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 用户数据访问。
 */
@Mapper
public interface UserMapper extends BaseMapper<AppUser> {
    /**
     * 批量读取公开资料所需字段。
     * @param ids 非空的用户 ID 集合
     * @return 只填充 id、nickname、avatarFileId 的资料投影，软删除由全局配置过滤
     */
    default List<AppUser> selectPublicProfiles(Collection<UUID> ids) {
        // 公开资料只投影展示字段，账号与密码保持在其各自业务查询中。
        return selectList(Wrappers.<AppUser>lambdaQuery()
                .select(AppUser::getId, AppUser::getNickname, AppUser::getAvatarFileId)
                .in(AppUser::getId, ids));
    }

    default int setPassword(UUID userId, String password) {
        return update(Wrappers.<AppUser>lambdaUpdate()
                .set(AppUser::getPasswordHash,password)
                .eq(AppUser::getId, userId));
    }
}