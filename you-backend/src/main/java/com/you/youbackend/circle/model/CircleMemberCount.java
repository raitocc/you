package com.you.youbackend.circle.model;
import java.util.UUID;
/** 按圈子聚合的有效成员数。 */
public record CircleMemberCount(UUID circleId, long memberCount) {}
