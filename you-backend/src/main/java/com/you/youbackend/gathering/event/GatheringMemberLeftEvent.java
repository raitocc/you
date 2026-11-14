package com.you.youbackend.gathering.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 成员已退出同行，原业务同时解除了该作者的瞬间关联。
 * @param gatheringId 来源同行
 * @param userId 离开成员，也是在此同行中解除关联的瞬间作者
 * @param leftAt 退出时间，UTC 毫秒精度
 */
public record GatheringMemberLeftEvent(UUID gatheringId, UUID userId, LocalDateTime leftAt) {}
