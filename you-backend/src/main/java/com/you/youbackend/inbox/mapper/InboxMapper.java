package com.you.youbackend.inbox.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.common.enumeration.ObjectType;
import com.you.youbackend.common.enumeration.SourceType;
import com.you.youbackend.inbox.entity.UserInbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface InboxMapper extends BaseMapper<UserInbox> {

    /**
     * 将新增的信箱行记录 rows 写入 user_inbox 表
     * @param rows 信箱行记录 rows
     */
    void insertMessages(@Param("rows") List<UserInbox> rows);

    /**
     * 将同行里被移除的一个或多个瞬间在 user_inbox 里的记录标记为失效，delete_at:removedAt
     * @param gatheringId 同行 ID
     * @param momentIds 瞬间 ID 列表
     * @param removedAt 删除时间
     */
    default void invalidateGatheringMoments(UUID gatheringId, List<UUID> momentIds, Instant removedAt) {
        update(invalidation(removedAt)
                .eq(UserInbox::getSourceType,SourceType.gathering)
                .eq(UserInbox::getSourceId,gatheringId)
                .eq(UserInbox::getObjectType, ObjectType.moment)
                .in(UserInbox::getObjectId,momentIds));
    }

    /**
     * 将圈子里的一条被撤回的分享，将与所有有效圈子成员的 user_inbox 记录都标记为失效
     * @param circleId 圈子 ID
     * @param shareId circle_share ID
     * @param withdrawnAt 撤回时间
     */
    default void invalidateCircleShare(UUID circleId, UUID shareId, Instant withdrawnAt) {
        update(invalidation(withdrawnAt)
                .eq(UserInbox::getSourceType,SourceType.circle)
                .eq(UserInbox::getSourceId,circleId)
                .eq(UserInbox::getShareId, shareId));
    }

    /**
     * 成员离开空间（圈子、同行）后，将其在该空间发送或收到的 user_inbox 记录标记为失效
     * @param sourceType 来源类型，同行或圈子
     * @param sourceId 来源 ID，同行或圈子的 ID
     * @param userId 离开、被移除的成员用户 ID
     * @param leftAt 离开、移除时间
     */
    default void invalidateMemberContents(SourceType sourceType, UUID sourceId, UUID userId, Instant leftAt) {
        update(invalidation(leftAt)
                .eq(UserInbox::getSourceType, sourceType)
                .eq(UserInbox::getSourceId, sourceId)
                .and(wrapper -> wrapper
                        .eq(UserInbox::getActorUserId, userId)
                        .or()
                        .eq(UserInbox::getRecipientUserId, userId)));
    }

    /**
     * 空间（圈子、同行）解散后，将该空间产生的所有 user_inbox 记录标记为失效
     * @param sourceType 来源类型，同行或圈子
     * @param sourceId 来源 ID，同行或圈子的 ID
     * @param dissolvedAt 解散时间
     */
    default void invalidateSourceContents(SourceType sourceType, UUID sourceId, Instant dissolvedAt) {
        update(invalidation(dissolvedAt)
                .eq(UserInbox::getSourceType, sourceType)
                .eq(UserInbox::getSourceId, sourceId));
    }

    /**
     * 原内容（瞬间、时光）删除后，将该内容在所有来源产生的 user_inbox 记录标记为失效
     * @param objectType 内容类型，瞬间或时光
     * @param objectId 内容 ID，瞬间或时光的 ID
     * @param deletedAt 删除时间
     */
    default void invalidateObjectContents(ObjectType objectType, UUID objectId, Instant deletedAt) {
        update(invalidation(deletedAt)
                .eq(UserInbox::getObjectType, objectType)
                .eq(UserInbox::getObjectId, objectId));
    }

    /**
     * 辅助方法：将 user_inbox 中的一条记录标记为失效
     * @param time 时间
     */
    private static LambdaUpdateWrapper<UserInbox> invalidation(Instant time) {
        return Wrappers.<UserInbox>lambdaUpdate()
                .set(UserInbox::getDeletedAt, time)
                .set(UserInbox::getUpdatedAt, time);
    }

}
