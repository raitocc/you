package com.you.youbackend.inbox.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.inbox.entity.UserInbox;
import com.you.youbackend.inbox.model.InboxSourceType;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 用户信箱的批量投递与失效写入，参与原业务事务。 */
@Mapper
public interface UserInboxMapper extends BaseMapper<UserInbox> {
    /**
     * 读取接收者的有效信箱候选，来源及内容有效性由 Service 批量筛选。
     * @param userId 当前认证接收者
     * @param sharedAt 上批末条候选的 UTC 分享时间，首屏为 null
     * @param id 同一条候选的动态 ID，与时间成组提供
     * @param rowCount 本批读取数量
     * @return 按分享时间、动态 ID 倒序的完整信箱实体
     */
    default List<UserInbox> selectPageCandidates(UUID userId, LocalDateTime sharedAt, UUID id, int rowCount) {
        // 1. 限定本人信箱，逻辑删除沿用全局配置。
        var query = Wrappers.<UserInbox>lambdaQuery().eq(UserInbox::getRecipientUserId, userId);
        // 2. 沿原始候选推进边界，来源失效的记录也计入扫描位置。
        if (sharedAt != null) {
            query.and(boundary -> boundary.lt(UserInbox::getSharedAt, sharedAt)
                    .or(tie -> tie.eq(UserInbox::getSharedAt, sharedAt).lt(UserInbox::getId, id)));
        }
        return selectList(query.orderByDesc(UserInbox::getSharedAt, UserInbox::getId).last("LIMIT " + rowCount));
    }

    /**
     * 一条 SQL 保存本批动态，数据库错误沿原事务抛出。
     * @param rows 非空列表，字段已由信箱业务构造；每条使用独立 UUIDv7
     * @return 实际插入条数
     */
    @Insert("""
            <script>
            INSERT INTO user_inbox
                (id, created_at, updated_at, recipient_user_id, actor_user_id,
                 source_type, source_id, object_type, object_id, shared_at, share_id)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.id}, #{row.createdAt}, #{row.updatedAt}, #{row.recipientUserId}, #{row.actorUserId},
                 #{row.sourceType}, #{row.sourceId}, #{row.objectType}, #{row.objectId},
                 #{row.sharedAt}, #{row.shareId,jdbcType=BINARY})
            </foreach>
            </script>
            """)
    int insertMessages(@Param("rows") List<UserInbox> rows);

    /** 作废指定圈子分享的有效动态，原撤回记录保持原失效时间。 */
    default int invalidateCircleShare(UUID shareId, LocalDateTime now) {
        // shareId 是全局唯一的圈子分享引用，所有接收者的对应入口共同失效。
        return update(invalidation(now).eq(UserInbox::getSourceType, InboxSourceType.circle)
                .eq(UserInbox::getShareId, shareId));
    }

    /** 作废本次取消关联的同行瞬间动态，调用方传入非空 ID 列表。 */
    default int invalidateGatheringMoments(UUID gatheringId, List<UUID> momentIds, LocalDateTime now) {
        // 来源和内容 ID 共同限定，其他同行及圈子中的独立卡片保留。
        return update(invalidation(now).eq(UserInbox::getSourceType, InboxSourceType.gathering)
                .eq(UserInbox::getSourceId, gatheringId).in(UserInbox::getObjectId, momentIds));
    }

    /** 作废离开成员在该空间发出的内容动态，保留其收到的其他成员动态。 */
    default int invalidateActorContents(InboxSourceType sourceType, UUID sourceId, UUID actorId, LocalDateTime now) {
        // actorUserId 表达内容分享者，此处与 recipientUserId 的接收者含义分开。
        return update(invalidation(now).eq(UserInbox::getSourceType, sourceType)
                .eq(UserInbox::getSourceId, sourceId).eq(UserInbox::getActorUserId, actorId));
    }

    /** 构造有效记录的失效更新，复用全局逻辑删除过滤。 */
    private static LambdaUpdateWrapper<UserInbox> invalidation(LocalDateTime now) {
        // 只设置失效与修改时间，分享时间和原投递内容保持原样。
        return Wrappers.<UserInbox>lambdaUpdate()
                .set(UserInbox::getDeletedAt, now).set(UserInbox::getUpdatedAt, now);
    }
}
