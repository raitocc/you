package com.you.youbackend.circle.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.circle.entity.Circle;
import org.apache.ibatis.annotations.Mapper;
import java.util.UUID;
/** 圈子读写，沿用全局逻辑删除。 */
@Mapper
public interface CircleMapper extends BaseMapper<Circle> {
    /** 通过邀请码唯一索引定位有效圈子。 */
    default Circle findByInviteCode(long code) {
        return selectOne(Wrappers.<Circle>lambdaQuery().eq(Circle::getInviteCode, code));
    }

    /** 仅写入邀请开关和修改时间。 */
    default int updateInviteEnabled(Circle circle) {
        return update(Wrappers.<Circle>lambdaUpdate().eq(Circle::getId, circle.getId())
                .set(Circle::getInviteEnabled, circle.getInviteEnabled())
                .set(Circle::getUpdatedAt, circle.getUpdatedAt()));
    }

    /** 仅写入新邀请码和修改时间。 */
    default int updateInviteCode(Circle circle) {
        return update(Wrappers.<Circle>lambdaUpdate().eq(Circle::getId, circle.getId())
                .set(Circle::getInviteCode, circle.getInviteCode())
                .set(Circle::getUpdatedAt, circle.getUpdatedAt()));
    }

    /** 显式更新可空字段，保留创建者、邀请信息及创建时间。 */
    default int updateContent(Circle circle) {
        // null 必须写入数据库以清空介绍和自定义头像。
        return update(Wrappers.<Circle>lambdaUpdate().eq(Circle::getId, circle.getId())
                .eq(Circle::getCreatorUserId, circle.getCreatorUserId())
                .set(Circle::getName, circle.getName()).set(Circle::getDescription, circle.getDescription())
                .set(Circle::getAvatarFileId, circle.getAvatarFileId()).set(Circle::getUpdatedAt, circle.getUpdatedAt()));
    }
}
