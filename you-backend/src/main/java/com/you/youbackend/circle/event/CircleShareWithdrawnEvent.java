package com.you.youbackend.circle.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 事件：撤回一个圈子里的一条分享
 * 发布入口：CircleService.withdrawShare()
 * @param circleId 圈子 ID
 * @param shareId 分享 ID
 * @param withdrawnAt 撤回时间
 */
public record CircleShareWithdrawnEvent(
        UUID circleId,
        UUID shareId,
        LocalDateTime withdrawnAt
) {
}
