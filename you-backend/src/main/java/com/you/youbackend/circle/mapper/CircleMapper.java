package com.you.youbackend.circle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.Circle;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.UUID;

/** 圈子主表读写，复用全局 UUID 映射与软删除配置。 */
@Mapper
public interface CircleMapper extends BaseMapper<Circle> {
    /**
     * 根据长期邀请码读取未解散的圈子，邀请开关由业务层判断。
     * @param code 已通过非空白与长度校验的邀请码
     * @return 完整圈子实体，不存在或已解散时为 null
     */
    default Circle selectByInviteCode(String code) {
        // 唯一邀请码定位圈子，逻辑删除过滤由 MyBatis-Plus 自动补充。
        return selectOne(Wrappers.<Circle>lambdaQuery().eq(Circle::getInviteCode, code));
    }

    /**
     * 仅写入邀请开关和修改时间，邀请码保持不变。
     * @param circle 已填充目标开关状态和修改时间的有效圈子
     * @return 更新行数
     */
    default int updateInviteEnabled(Circle circle) {
        return update(Wrappers.<Circle>lambdaUpdate()
                .eq(Circle::getId, circle.getId())
                .set(Circle::getInviteEnabled, circle.getInviteEnabled())
                .set(Circle::getUpdatedAt, circle.getUpdatedAt()));
    }

    /**
     * 仅写入新邀请码和修改时间，邀请开关保持不变。
     * @param circle 已填充新邀请码和修改时间的有效圈子
     * @return 更新行数
     */
    default int updateInviteCode(Circle circle) {
        return update(Wrappers.<Circle>lambdaUpdate()
                .eq(Circle::getId, circle.getId())
                .set(Circle::getInviteCode, circle.getInviteCode())
                .set(Circle::getUpdatedAt, circle.getUpdatedAt()));
    }

    /**
     * 全量保存名称、介绍、自定义头像和修改时间；null 介绍和 null 头像显式写入数据库以清空。
     * 创建者条件是并发防护：只有仍是创建者时才落库。
     * @param circle 已校验创建者权限并填充最终字段的有效圈子
     * @return 更新行数
     */
    default int updateContentById(Circle circle) {
        return update(Wrappers.<Circle>lambdaUpdate().eq(Circle::getId, circle.getId())
                .eq(Circle::getCreatorUserId, circle.getCreatorUserId())
                .set(Circle::getName, circle.getName()).set(Circle::getDescription, circle.getDescription())
                .set(Circle::getAvatarFileId, circle.getAvatarFileId()).set(Circle::getUpdatedAt, circle.getUpdatedAt()));
    }

    /**
     * 软删除圈子（解散），解散时间同时作为修改时间，并关闭邀请入口。
     * 创建者条件是并发防护：只有仍是创建者时才落库。
     * @param circleId 有效圈子 ID
     * @param creatorId 创建者 ID，用于更新时复核身份
     * @param now 本次解散时间，UTC，同时写入 deletedAt 和 updatedAt
     * @return 更新行数
     */
    default int deleteByCircleIdAndCreatorUserId(UUID circleId, UUID creatorId, Instant now) {
        return update(Wrappers.<Circle>lambdaUpdate()
                .eq(Circle::getId, circleId)
                .eq(Circle::getCreatorUserId, creatorId)
                .set(Circle::getInviteEnabled, false)
                .set(Circle::getDeletedAt, now).set(Circle::getUpdatedAt, now));
    }
}
