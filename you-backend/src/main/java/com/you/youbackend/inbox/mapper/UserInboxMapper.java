package com.you.youbackend.inbox.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.inbox.entity.UserInbox;
import com.you.youbackend.inbox.model.InboxSourceType;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 用户信箱的批量投递与失效写入，参与原业务事务。 */
@Mapper
public interface UserInboxMapper extends BaseMapper<UserInbox> {
    /**
     * 先筛选当前可访问动态，再按分享时间、信箱 ID 倒序分页。
     * @param userId 当前认证接收者
     * @param sharedAt UTC 毫秒游标时间，首屏为 null
     * @param id 同一条信箱动态 ID，与时间成组提供
     * @param rowCount 页大小加一，用于判断后续页
     * @return 有效来源、关联与原内容已筛选的完整信箱实体
     */
    @Select("""
            <script>
            SELECT i.id, i.created_at, i.updated_at, i.recipient_user_id, i.actor_user_id,
                   i.source_type, i.source_id, i.object_type, i.object_id,
                   i.shared_at, i.share_id, i.read_at, i.deleted_at
            FROM user_inbox i
            WHERE i.recipient_user_id = #{userId} AND i.deleted_at IS NULL
              <!-- 同行：当前成员、来源和原瞬间关联共同有效。 -->
              AND (
                (i.source_type = 'gathering' AND i.object_type = 'moment' AND EXISTS (
                    SELECT 1 FROM gathering g
                    JOIN gathering_member gm ON gm.gathering_id = g.id
                    JOIN moment m ON m.gathering_id = g.id
                    WHERE g.id = i.source_id AND g.deleted_at IS NULL
                      AND gm.user_id = #{userId} AND gm.deleted_at IS NULL
                      AND m.id = i.object_id AND m.deleted_at IS NULL
                      AND m.author_user_id = i.actor_user_id
                ))
                OR
                <!-- 圈子：当前接收者、分享者、分享引用均有效，原内容仍属于分享者。 -->
                (i.source_type = 'circle' AND EXISTS (
                    SELECT 1 FROM circle c
                    JOIN circle_member receiver ON receiver.circle_id = c.id
                    JOIN circle_share s ON s.circle_id = c.id
                    JOIN circle_member sender ON sender.circle_id = c.id AND sender.user_id = s.sharer_user_id
                    WHERE c.id = i.source_id AND c.deleted_at IS NULL
                      AND receiver.user_id = #{userId} AND receiver.deleted_at IS NULL
                      AND sender.deleted_at IS NULL AND s.deleted_at IS NULL
                      AND s.id = i.share_id AND s.object_type = i.object_type AND s.object_id = i.object_id
                      AND s.sharer_user_id = i.actor_user_id
                      AND (
                        (s.object_type = 'moment' AND EXISTS (
                            SELECT 1 FROM moment m WHERE m.id = s.object_id
                              AND m.author_user_id = s.sharer_user_id AND m.deleted_at IS NULL
                        ))
                        OR (s.object_type = 'album' AND EXISTS (
                            SELECT 1 FROM album a WHERE a.id = s.object_id
                              AND a.owner_user_id = s.sharer_user_id AND a.deleted_at IS NULL
                        ))
                      )
                ))
              )
            <if test="sharedAt != null">
              AND (i.shared_at &lt; #{sharedAt} OR (i.shared_at = #{sharedAt} AND i.id &lt; #{id}))
            </if>
            ORDER BY i.shared_at DESC, i.id DESC
            LIMIT #{rowCount}
            </script>
            """)
    List<UserInbox> selectRelatedPage(@Param("userId") UUID userId, @Param("sharedAt") LocalDateTime sharedAt,
                                    @Param("id") UUID id, @Param("rowCount") int rowCount);

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
