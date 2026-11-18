package com.you.youbackend.inbox.listener;

import com.you.youbackend.circle.event.CircleContentsSharedEvent;
import com.you.youbackend.circle.event.CircleMemberLeftEvent;
import com.you.youbackend.circle.event.CircleShareWithdrawnEvent;
import com.you.youbackend.gathering.event.GatheringMemberLeftEvent;
import com.you.youbackend.gathering.event.GatheringMomentsLinkedEvent;
import com.you.youbackend.gathering.event.GatheringMomentsUnlinkedEvent;
import com.you.youbackend.common.enumeration.SourceType;
import com.you.youbackend.inbox.service.InboxService;
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
     * 监听事件：一个或多个瞬间已加入同行
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
     * 监听事件：一个或多个瞬间已从同行中移出
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
     * 业务方法：把离开、被移除的成员（圈子、同行）在信箱发送的记录标记为失效
     */
    @EventListener
    public void onGatheringMemberLeft(GatheringMemberLeftEvent event) {
        inboxService.invalidateActorContents(
                SourceType.gathering,
                event.gatheringId(),
                event.userId(),
                event.leftAt()
        );
    }

    /**
     * 监听事件：一条瞬间或时光分享到一个或多个圈子
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
     * 监听事件：撤回一个圈子里的一条分享
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
     * 监听事件：一名成员离开圈子或被圈子移除
     * 业务方法：把离开、被移除的成员（圈子、同行）在信箱发送的记录标记为失效
     */
    @EventListener
    public void onCircleMemberLeft(CircleMemberLeftEvent event) {
        inboxService.invalidateActorContents(
                SourceType.circle,
                event.circleId(),
                event.userId(),
                event.leftAt()
        );
    }
}
