package com.you.youbackend.inbox.listener;

import com.you.youbackend.circle.event.CircleContentsSharedEvent;
import com.you.youbackend.circle.event.CircleMemberLeftEvent;
import com.you.youbackend.circle.event.CircleShareWithdrawnEvent;
import com.you.youbackend.gathering.event.GatheringMemberLeftEvent;
import com.you.youbackend.gathering.event.GatheringMomentsLinkedEvent;
import com.you.youbackend.gathering.event.GatheringMomentsUnlinkedEvent;
import com.you.youbackend.inbox.model.InboxSourceType;
import com.you.youbackend.inbox.service.InboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 同步接收原业务事件并委托信箱写入；监听异常沿原调用链触发事务回滚。 */
@Component
@RequiredArgsConstructor
public class InboxEventListener {
    private final InboxService inboxService;

    /** 同行新增关联后投递给当前成员。 */
    @EventListener
    public void onGatheringMomentsLinked(GatheringMomentsLinkedEvent event) {
        // 沿用原业务已确认的新增瞬间集合和时间。
        inboxService.deliverGatheringMoments(event.gatheringId(), event.actorUserId(), event.momentIds(), event.sharedAt());
    }

    /** 圈子新增或恢复分享后投递，已有有效分享由原业务排除。 */
    @EventListener
    public void onCircleContentsShared(CircleContentsSharedEvent event) {
        // 所有目标圈子统一交给信箱业务批量处理。
        inboxService.deliverCircleContents(event.actorUserId(), event.contents(), event.sharedAt());
    }

    /** 同行取消关联后作废旧入口。 */
    @EventListener
    public void onGatheringMomentsUnlinked(GatheringMomentsUnlinkedEvent event) {
        // 使用实际取消关联的内容范围，其他来源独立保留。
        inboxService.invalidateGatheringMoments(event.gatheringId(), event.momentIds(), event.removedAt());
    }

    /** 圈子撤回分享后作废旧入口，恢复分享会另行产生新动态。 */
    @EventListener
    public void onCircleShareWithdrawn(CircleShareWithdrawnEvent event) {
        // 以分享关系 ID 精确定位所有接收者的对应动态。
        inboxService.invalidateCircleShare(event.circleId(), event.shareId(), event.withdrawnAt());
    }

    /** 同行成员退出时作废该作者已解除关联的内容，保留其接收历史。 */
    @EventListener
    public void onGatheringMemberLeft(GatheringMemberLeftEvent event) {
        // actor 表达离开成员发出的内容，接收者的其他动态保持原样。
        inboxService.invalidateActorContents(InboxSourceType.gathering, event.gatheringId(), event.userId(), event.leftAt());
    }

    /** 圈子成员退出、被移除时作废其已撤回的分享，保留其接收历史。 */
    @EventListener
    public void onCircleMemberLeft(CircleMemberLeftEvent event) {
        // 退出和管理移除共用原分享撤销结果。
        inboxService.invalidateActorContents(InboxSourceType.circle, event.circleId(), event.userId(), event.leftAt());
    }
}
