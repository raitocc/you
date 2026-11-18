package com.you.youbackend.inbox.listener;

import com.you.youbackend.album.event.AlbumDeletedEvent;
import com.you.youbackend.circle.event.CircleContentsSharedEvent;
import com.you.youbackend.circle.event.CircleDissolvedEvent;
import com.you.youbackend.circle.event.CircleMemberLeftEvent;
import com.you.youbackend.circle.event.CircleShareWithdrawnEvent;
import com.you.youbackend.common.enumeration.ObjectType;
import com.you.youbackend.gathering.event.GatheringDissolvedEvent;
import com.you.youbackend.gathering.event.GatheringMemberLeftEvent;
import com.you.youbackend.gathering.event.GatheringMomentsLinkedEvent;
import com.you.youbackend.gathering.event.GatheringMomentsUnlinkedEvent;
import com.you.youbackend.common.enumeration.SourceType;
import com.you.youbackend.inbox.service.InboxService;
import com.you.youbackend.moment.event.MomentDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Inbox 监听器类，用于接收圈子和同行模块的业务事件
 */
@Component
@RequiredArgsConstructor
public class InboxEventListener {

    private final InboxService inboxService;

    /**
     * 监听事件：一个或多个瞬间已关联到同行
     * 业务方法：业务方法：将分享到同行的一个或多个瞬间写入 user_inbox：【接收者是同行内的有效成员，包括分享者自己】
     */
    @EventListener
    public void onGatheringMomentsLinked(GatheringMomentsLinkedEvent event) {
        inboxService.deliverGatheringMoments(
                event.gatheringId(),
                event.actorUserId(),
                event.momentIds(),
                event.sharedAt()
        );
    }

    /**
     * 监听事件：一个或多个瞬间已从同行移出
     * 业务方法：将一个或多个瞬间在信箱 user_inbox 里的记录标记为失效，delete_at
     */
    @EventListener
    public void onGatheringMomentsUnlinked(GatheringMomentsUnlinkedEvent event) {
        inboxService.invalidateGatheringMoments(
                event.gatheringId(),
                event.momentIds(),
                event.removedAt()
        );
    }

    /**
     * 监听事件：一名成员已退出同行
     * 业务方法：将该成员在这个同行发送或收到的 user_inbox 记录标记为失效
     */
    @EventListener
    public void onGatheringMemberLeft(GatheringMemberLeftEvent event) {
        inboxService.invalidateMemberContents(
                SourceType.gathering,
                event.gatheringId(),
                event.userId(),
                event.leftAt()
        );
    }

    /**
     * 监听事件：一个瞬间或时光已分享到一个或多个圈子
     * 业务方法：将分享到圈子的一个瞬间或时光写入 user_inbox：【接收者是圈子内的有效成员，包括分享者自己】
     */
    @EventListener
    public void onCircleContentsShared(CircleContentsSharedEvent event) {
        inboxService.deliverCircleContents(
                event.actorUserId(),
                event.contents(),
                event.sharedAt()
        );
    }

    /**
     * 监听事件：圈子里的一条具体分享已撤回
     * 业务方法：将圈子里的一条瞬间或时光在信箱 user_inbox 里的记录标记为失效，delete_at
     */
    @EventListener
    public void onCircleShareWithdrawn(CircleShareWithdrawnEvent event) {
        inboxService.invalidateCircleShare(
                event.circleId(),
                event.shareId(),
                event.withdrawnAt()
        );
    }

    /**
     * 监听事件：一名成员已退出圈子或被移除
     * 业务方法：将该成员在这个圈子发送或收到的 user_inbox 记录标记为失效
     */
    @EventListener
    public void onCircleMemberLeft(CircleMemberLeftEvent event) {
        inboxService.invalidateMemberContents(
                SourceType.circle,
                event.circleId(),
                event.userId(),
                event.leftAt()
        );
    }

    /**
     * 监听事件：一个同行已解散
     * 业务方法：将这个同行产生的所有 user_inbox 记录标记为失效
     */
    @EventListener
    public void onGatheringDissolved(GatheringDissolvedEvent event) {
        inboxService.invalidateSourceContents(
                SourceType.gathering,
                event.gatheringId(),
                event.dissolvedAt()
        );
    }

    /**
     * 监听事件：一个圈子已解散
     * 业务方法：将这个圈子产生的所有 user_inbox 记录标记为失效
     */
    @EventListener
    public void onCircleDissolved(CircleDissolvedEvent event) {
        inboxService.invalidateSourceContents(
                SourceType.circle,
                event.circleId(),
                event.dissolvedAt()
        );
    }

    /**
     * 监听事件：一个瞬间已删除
     * 业务方法：将这个瞬间在所有同行、圈子产生的 user_inbox 记录标记为失效
     */
    @EventListener
    public void onMomentDeleted(MomentDeletedEvent event) {
        inboxService.invalidateObjectContents(
                ObjectType.moment,
                event.momentId(),
                event.deletedAt()
        );
    }

    /**
     * 监听事件：一个时光已删除
     * 业务方法：将这个时光在所有圈子产生的 user_inbox 记录标记为失效
     */
    @EventListener
    public void onAlbumDeleted(AlbumDeletedEvent event) {
        inboxService.invalidateObjectContents(
                ObjectType.album,
                event.albumId(),
                event.deletedAt()
        );
    }
}
