package com.you.youbackend.circle.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 一条圈子分享已撤回。
 * @param circleId 来源圈子 ID
 * @param shareId 本次撤回的分享关系 ID
 * @param withdrawnAt 撤回时间，UTC 毫秒精度
 */
public record CircleShareWithdrawnEvent(UUID circleId, UUID shareId, LocalDateTime withdrawnAt) {}
