package com.you.youbackend.inbox.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.circle.entity.CircleMember;
import com.you.youbackend.circle.mapper.CircleMemberMapper;
import com.you.youbackend.circle.model.CircleSharedContent;
import com.you.youbackend.common.enumeration.ObjectType;
import com.you.youbackend.common.enumeration.SourceType;
import com.you.youbackend.common.util.TimeUtils;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.inbox.entity.UserInbox;
import com.you.youbackend.inbox.mapper.InboxMapper;
import com.you.youbackend.inbox.model.DeliveryContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY) // 使用强制事务：事务方法必须被事务方法调用
public class InboxService {

    private final InboxMapper inboxMapper;
    private final GatheringMemberMapper gatheringMemberMapper;
    private final CircleMemberMapper circleMemberMapper;

    /**
     * 监听事件：一个或多个瞬间已关联到同行
     * 业务方法：将分享到同行的一个或多个瞬间写入 user_inbox：【接收者是同行内的有效成员，包括分享者自己】
     * @param gatheringId 同行 ID
     * @param actorUserId 分享者用户 ID
     * @param momentIds 瞬间 ID 列表
     * @param sharedAt 分享时间
     */
    public void deliverGatheringMoments(
            UUID gatheringId,
            UUID actorUserId,
            List<UUID> momentIds,
            Instant sharedAt
    ) {
        if (momentIds == null || momentIds.isEmpty()) {
            log.warn("传入瞬间列表为空");
            return;
        }

        // 1.通过 gathering 模块服务获取相关数据
        // 通过同行 ID 获取有效用户 ID 列表
        List<UUID> recipients = gatheringMemberMapper.selectActiveUserIds(gatheringId);
        Instant createdAt = TimeUtils.now();
        // 创建 user_inbox 表行数据列表 rows
        List<UserInbox> rows = new ArrayList<>();

        // 2.构建 user_inbox 记录列表 rows
        // 构建即将插入的行数据列表 rows
        for (UUID momentId : momentIds) {
            // 构建接收者共用 DTO DeliveryContent，这里是在时光里分享的瞬间
            DeliveryContent content = new DeliveryContent(
                    SourceType.gathering,
                    gatheringId,
                    actorUserId,
                    ObjectType.moment,
                    momentId,
                    null,
                    sharedAt
            );

            // 将 DeliveryContent 分发给每个接收者，新增一条 user_inbox 记录
            for (UUID recipientId : recipients) {
                rows.add(createRow(content, recipientId, createdAt));
            }
        }

        // 3.向 user_inbox 插入记录列表 rows
        inboxMapper.insertMessages(rows);

    }

    /**
     * 监听事件：一个或多个瞬间已从同行移出
     * 业务方法：将一个或多个瞬间在信箱 user_inbox 里的记录标记为失效，delete_at
     * @param gatheringId 同行 ID
     * @param momentIds 瞬间 ID 列表
     * @param removedAt 移除时间
     */
    public void invalidateGatheringMoments(
            UUID gatheringId,
            List<UUID> momentIds,
            Instant removedAt
    ) {
        if (!momentIds.isEmpty()) {
            inboxMapper.invalidateGatheringMoments(gatheringId, momentIds, removedAt);
        }
    }

    /**
     * 监听事件：一个瞬间或时光已分享到一个或多个圈子
     * 业务方法：将分享到圈子的一个瞬间或时光写入 user_inbox：【接收者是圈子内的有效成员，包括分享者自己】
     * @param actorUserId 分享者用户 ID
     * @param contents 圈子的一条分享对象 CircleSharedContent 的列表
     * @param sharedAt 分享时间
     */
    public void deliverCircleContents(
            UUID actorUserId,
            List<CircleSharedContent> contents,
            Instant sharedAt
    ) {
        // 1.通过 circle 模块服务获取相关数据
        if (contents.isEmpty()) {
            return;
        }

        // 从分享对象中获取去重以后的目标分享圈子 ID 列表
        List<UUID> circleIds = contents.stream()
                .map(CircleSharedContent::circleId)
                .distinct()
                .toList();

        // 建立 Map(circleId 圈子 ID ：list<userId> 该圈子的用户 ID 列表) 的映射
        Map<UUID, List<UUID>> recipientsByCircle = circleMemberMapper
                // 获取 circleId 下的所有有效 CircleMember
                .selectActiveRecipients(circleIds)
                .stream()
                .collect(Collectors.groupingBy(
                        // key 映射：用 circleId 作为 Map 的 key
                        CircleMember::getCircleId,
                        // value 映射：提取每个 CircleMember 的 userId 转为 list 作为 Map 的 value
                        Collectors.mapping(CircleMember::getUserId, Collectors.toList())
                ));

        // 2.构建 user_inbox 记录列表 rows
        Instant createdAt = TimeUtils.now();
        List<UserInbox> rows = new ArrayList<>();

        for (CircleSharedContent shared : contents) {
            // 构建接收者共用 DTO DeliveryContent，这里是在圈子里分享的瞬间或时光
            DeliveryContent content = new DeliveryContent(
                    SourceType.circle,
                    shared.circleId(),
                    actorUserId,
                    shared.objectType(),
                    shared.objectId(),
                    shared.shareId(),
                    sharedAt
            );

            // 根据圈子 ID circleId 获取圈子里的用户 ID 列表
            List<UUID> recipients = recipientsByCircle.getOrDefault(
                    shared.circleId(), List.of()
            );

            // 将 DeliveryContent 与当前圈子里所有有效成员建立 user_inbox 关联
            for (UUID recipientId : recipients) {
                rows.add(createRow(content, recipientId, createdAt));
            }
        }

        // 3.向 user_inbox 插入记录列表 rows
        inboxMapper.insertMessages(rows);
    }

    /**
     * 监听事件：撤回一个圈子里的一条分享
     * 业务方法：将圈子里的一条瞬间或时光在信箱 user_inbox 里的记录标记为失效，delete_at
     * @param circleId 圈子 ID
     * @param shareId circle_share ID
     * @param withdrawnAt 撤回时间
     */
    public void invalidateCircleShare(
            UUID circleId,
            UUID shareId,
            Instant withdrawnAt
    ) {
        inboxMapper.invalidateCircleShare(circleId, shareId, withdrawnAt);
    }

    /**
     * 相关事件：一名成员已退出同行、一名成员已退出圈子或被移除
     * 业务方法：将该成员在指定空间发送或收到的 user_inbox 记录标记为失效
     * @param sourceType 来源类型，同行或圈子
     * @param sourceId 来源 ID，同行或圈子的 ID
     * @param userId 离开、被移除的成员用户 ID
     * @param leftAt 离开、移除时间
     */
    public void invalidateMemberContents(
            SourceType sourceType,
            UUID sourceId,
            UUID userId,
            Instant leftAt
    ) {
        inboxMapper.invalidateMemberContents(sourceType, sourceId, userId, leftAt);
    }

    /**
     * 相关事件：一个同行已解散、一个圈子已解散
     * 业务方法：将指定空间产生的所有 user_inbox 记录标记为失效
     * @param sourceType 来源类型，同行或圈子
     * @param sourceId 来源 ID，同行或圈子的 ID
     * @param dissolvedAt 解散时间
     */
    public void invalidateSourceContents(
            SourceType sourceType,
            UUID sourceId,
            Instant dissolvedAt
    ) {
        inboxMapper.invalidateSourceContents(sourceType, sourceId, dissolvedAt);
    }

    /**
     * 相关事件：一个瞬间已删除、一个时光已删除
     * 业务方法：将指定内容在所有来源产生的 user_inbox 记录标记为失效
     * @param objectType 内容类型，瞬间或时光
     * @param objectId 内容 ID，瞬间或时光的 ID
     * @param deletedAt 删除时间
     */
    public void invalidateObjectContents(
            ObjectType objectType,
            UUID objectId,
            Instant deletedAt
    ) {
        inboxMapper.invalidateObjectContents(objectType, objectId, deletedAt);
    }

    /**
     * 辅助方法：构建 user_inbox 表里的一条记录对象 row
     */
    private UserInbox createRow(
            DeliveryContent content,
            UUID recipientId,
            Instant createdAt
    ) {
        return UserInbox.builder()
                .id(UuidCreator.getTimeOrderedEpoch())
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .recipientUserId(recipientId)
                .actorUserId(content.actorUserId())
                .sourceType(content.sourceType())
                .sourceId(content.sourceId())
                .objectType(content.objectType())
                .objectId(content.objectId())
                .shareId(content.shareId())
                .sharedAt(content.sharedAt())
                .build();
    }


}
