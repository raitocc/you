package com.you.youbackend.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.user.model.AppUser;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 用户数据访问。
 */
@Mapper
public interface UserMapper extends BaseMapper<AppUser> {
    /**
     * 批量读取公开展示所需字段。
     *
     * <p>只读查询，软删除由全局配置过滤。</p>
     *
     * @param ids 非空的用户 ID 集合
     * @return 只填充 id、nickname、avatarFileId 的资料投影
     */
    default List<AppUser> selectPublicProfiles(Collection<UUID> ids) {
        // 公开资料只投影展示字段，账号与密码保持在其各自业务查询中。
        return selectList(Wrappers.<AppUser>lambdaQuery()
                .select(AppUser::getId, AppUser::getNickname, AppUser::getAvatarFileId)
                .in(AppUser::getId, ids));
    }

    /**
     * 按用户名查账号字段，含密码散列，给需要比对密码的场景用。
     *
     * <p>只读查询，软删除由全局配置过滤。</p>
     *
     * @param username 已转小写的用户名
     * @return 账号实体，不存在返回 null；注意带密码散列，禁止外传
     */
    default AppUser selectWithPasswordHashByUsername(String username) {
        return selectOne(Wrappers.<AppUser>lambdaQuery()
                .select(
                        AppUser::getId,
                        AppUser::getUsername,
                        AppUser::getPasswordHash,
                        AppUser::getNickname,
                        AppUser::getAvatarFileId,
                        AppUser::getCreatedAt,
                        AppUser::getUpdatedAt)
                .eq(AppUser::getUsername, username));
    }

    /**
     * 按用户 ID 查本人资料所需字段（不含密码散列）。
     *
     * <p>只读查询，软删除由全局配置过滤。</p>
     *
     * @param userId 用户 ID
     * @return 账号实体，不存在返回 null
     */
    default AppUser selectProfileById(UUID userId) {
        return selectOne(Wrappers.<AppUser>lambdaQuery()
                .select(
                        AppUser::getId,
                        AppUser::getUsername,
                        AppUser::getNickname,
                        AppUser::getAvatarFileId,
                        AppUser::getCreatedAt,
                        AppUser::getUpdatedAt)
                .eq(AppUser::getId, userId));
    }

    /**
     * 全量覆盖本人昵称和头像，头像传 null 即移除。
     *
     * @param userId       用户 ID
     * @param nickname     已去首尾空白的昵称
     * @param avatarFileId 头像文件 ID，null 移除头像
     * @param updatedAt    本次统一的 UTC 毫秒时间
     * @return 更新行数
     */
    default int updateProfile(UUID userId, String nickname, UUID avatarFileId, Instant updatedAt) {
        return update(Wrappers.<AppUser>lambdaUpdate()
                .eq(AppUser::getId, userId)
                .set(AppUser::getNickname, nickname)
                .set(AppUser::getAvatarFileId, avatarFileId)
                .set(AppUser::getUpdatedAt, updatedAt));
    }

    /**
     * 覆写指定用户的密码散列，调用方负责把新密码编码好传进来。
     *
     * @param userId       用户 ID
     * @param passwordHash 已编码的密码散列，不是明文
     * @return 更新行数
     */
    default int updatePasswordHash(UUID userId, String passwordHash) {
        return update(Wrappers.<AppUser>lambdaUpdate()
                .eq(AppUser::getId, userId)
                .set(AppUser::getPasswordHash, passwordHash));
    }
}