package com.you.youbackend.inbox.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.circle.entity.CircleMember;
import com.you.youbackend.circle.mapper.CircleMemberMapper;
import com.you.youbackend.circle.model.CircleSharedContent;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.inbox.entity.UserInbox;
import com.you.youbackend.inbox.mapper.UserInboxMapper;
import com.you.youbackend.inbox.model.InboxContent;
import com.you.youbackend.inbox.model.InboxObjectType;
import com.you.youbackend.inbox.model.InboxSourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 信箱投递和旧动态失效；同步事件调用，所有写入参与原业务事务。 */
@Log4j2
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class InboxService {
    private final UserInboxMapper inboxMapper;
    private final GatheringMemberMapper gatheringMemberMapper;
    private final CircleMemberMapper circleMemberMapper;

    /**
     * 为本次实际新增的同行瞬间关联投递动态，操作者本人也在有效成员范围内。
     * @param gatheringId 已完成业务资格校验的同行 ID
     * @param actorId 本次关联操作者及瞬间作者
     * @param momentIds 实际新增关联的瞬间 ID，原业务已去重
     * @param sharedAt 本次关联时间，UTC 毫秒精度
     */
    public void deliverGatheringMoments(UUID gatheringId, UUID actorId, List<UUID> momentIds, LocalDateTime sharedAt) {
        // 1. 无新增关联时直接结束；一次读取当前成员，各瞬间共用接收范围。
        if (momentIds.isEmpty()) return;
        List<UUID> recipients = gatheringMemberMapper.selectActiveUserIds(gatheringId);
        LocalDateTime createdAt = utcNow();
        // 2. 每个瞬间为每个成员生成独立记录，内容与分享时间按本次行为保留。
        List<UserInbox> rows = new ArrayList<>();
        for (UUID momentId : momentIds) {
            var content = new InboxContent(InboxSourceType.gathering, gatheringId, actorId,
                    InboxObjectType.moment, momentId, null, sharedAt);
            rows.addAll(createMessages(content, recipients, createdAt));
        }
        // 3. 一次保存本批记录，异常沿同步事件返回，由原事务回滚。
        saveMessages(rows);
        log.info("已写入同行信箱投递，gatheringId={}，actorUserId={}，momentCount={}，recipientCount={}，messageCount={}",
                gatheringId, actorId, momentIds.size(), recipients.size(), rows.size());
    }

    /**
     * 为本次新增、恢复的圈子分享投递动态，批量读取所有目标圈子的有效成员。
     * @param actorId 分享者 ID
     * @param contents 原业务实际新增、恢复的分享引用
     * @param sharedAt 本次分享时间，UTC 毫秒精度
     */
    public void deliverCircleContents(UUID actorId, List<CircleSharedContent> contents, LocalDateTime sharedAt) {
        // 1. 仅处理实际变化的分享，按圈子批量查询并整理接收者。
        if (contents.isEmpty()) return;
        Map<UUID, List<UUID>> recipients = loadCircleRecipients(contents);
        LocalDateTime createdAt = utcNow();
        // 2. 每条分享保留自己的来源与 shareId，同一内容在不同圈子产生独立动态。
        List<UserInbox> rows = new ArrayList<>();
        for (CircleSharedContent shared : contents) {
            InboxObjectType type = switch (shared.objectType()) {
                case moment -> InboxObjectType.moment;
                case album -> InboxObjectType.album;
            };
            var content = new InboxContent(InboxSourceType.circle, shared.circleId(), actorId,
                    type, shared.objectId(), shared.shareId(), sharedAt);
            rows.addAll(createMessages(content, recipients.getOrDefault(shared.circleId(), List.of()), createdAt));
        }
        // 3. 按批写入，内容引用和投递记录在原分享事务内共同提交。
        saveMessages(rows);
        log.info("已写入圈子信箱投递，actorUserId={}，shareCount={}，messageCount={}", actorId, contents.size(), rows.size());
    }

    /** 作废一条已撤回分享的全部有效动态，重复撤回保持原失效时间。 */
    public void invalidateCircleShare(UUID circleId, UUID shareId, LocalDateTime now) {
        // 只作废该分享引用的入口，原内容和其他来源保持原样。
        int changed = inboxMapper.invalidateCircleShare(shareId, now);
        log.info("已写入圈子信箱动态失效，circleId={}，shareId={}，messageCount={}", circleId, shareId, changed);
    }

    /** 作废本次取消关联的同行瞬间入口，原业务负责保证取消关联已完成。 */
    public void invalidateGatheringMoments(UUID gatheringId, List<UUID> momentIds, LocalDateTime now) {
        // 空变更直接结束，实际移除内容通过来源及瞬间 ID 一起限定。
        if (momentIds.isEmpty()) return;
        int changed = inboxMapper.invalidateGatheringMoments(gatheringId, momentIds, now);
        log.info("已写入同行信箱动态失效，gatheringId={}，momentCount={}，messageCount={}",
                gatheringId, momentIds.size(), changed);
    }

    /** 成员离开时只作废其发出的内容动态，保留其收到的其他成员动态供重新加入恢复。 */
    public void invalidateActorContents(InboxSourceType sourceType, UUID sourceId, UUID actorId, LocalDateTime now) {
        // 原业务已撤销该成员的关联或分享；接收身份由后续列表按当前成员关系过滤。
        int changed = inboxMapper.invalidateActorContents(sourceType, sourceId, actorId, now);
        log.info("已写入离开成员内容动态失效，sourceType={}，sourceId={}，actorUserId={}，messageCount={}",
                sourceType, sourceId, actorId, changed);
    }

    /** 一次查询所有实际分享目标，按圈子整理接收者 ID，供本批内容复用。 */
    private Map<UUID, List<UUID>> loadCircleRecipients(List<CircleSharedContent> contents) {
        // 数据库返回 circleId、userId 投影，当前有效关系自然包含仍在圈子内的分享者。
        List<UUID> circleIds = contents.stream().map(CircleSharedContent::circleId).distinct().toList();
        return circleMemberMapper.selectActiveRecipients(circleIds).stream()
                .collect(Collectors.groupingBy(CircleMember::getCircleId,
                        Collectors.mapping(CircleMember::getUserId, Collectors.toList())));
    }

    /** 为一条内容构造各接收者的记录，只使用已加载的数据。 */
    private List<UserInbox> createMessages(InboxContent content, List<UUID> recipients, LocalDateTime createdAt) {
        // 每位接收者使用独立 UUIDv7，同一分享行为沿用相同 sharedAt。
        return recipients.stream().map(recipient -> toMessage(content, recipient, createdAt)).toList();
    }

    /** 将已确认的内容与接收者转换为信箱实体；已读与失效字段初始保持 null。 */
    private UserInbox toMessage(InboxContent content, UUID recipient, LocalDateTime createdAt) {
        // 设置引用、接收者和时间，原图文通过 objectId 留在对应业务表。
        UserInbox row = new UserInbox();
        row.setId(UuidCreator.getTimeOrderedEpoch());
        row.setCreatedAt(createdAt);
        row.setUpdatedAt(createdAt);
        row.setRecipientUserId(recipient);
        row.setActorUserId(content.actorUserId());
        row.setSourceType(content.sourceType());
        row.setSourceId(content.sourceId());
        row.setObjectType(content.objectType());
        row.setObjectId(content.objectId());
        row.setShareId(content.shareId());
        row.setSharedAt(content.sharedAt());
        return row;
    }

    /** 有实际接收记录时批量保存，空成员集合保持零投递。 */
    private void saveMessages(List<UserInbox> rows) {
        // 避免生成空 VALUES；异常由事务处理，原业务和信箱共同回滚。
        if (!rows.isEmpty()) inboxMapper.insertMessages(rows);
    }

    /** 返回信箱实际构造时间，UTC 毫秒精度，与分享发生时间分别表达。 */
    private LocalDateTime utcNow() {
        // 与数据库 DATETIME(3) 精度一致。
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
}
