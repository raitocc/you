package com.you.youbackend.circle.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 成员已退出或被移出圈子，原业务同时撤回了该成员的全部圈内分享。
 * @param circleId 来源圈子 ID
 * @param userId 离开成员及原分享者 ID
 * @param leftAt 离开时间，UTC 毫秒精度
 */
public record CircleMemberLeftEvent(UUID circleId, UUID userId, LocalDateTime leftAt) {}
