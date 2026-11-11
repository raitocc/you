package com.you.youbackend.circle.response;
import com.you.youbackend.user.response.PublicUserResponse;
import java.time.Instant;
/** 被限制用户的公开资料及移除并设置限制的时间，时间为 UTC。 */
public record CircleJoinRestrictionResponse(
        PublicUserResponse user,
        Instant restrictedAt
) {}
