package com.you.youbackend.circle.response;
import com.you.youbackend.user.response.PublicUserResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 圈子详情，包含创建者公开资料和按加入时间排列的全部有效成员。 */
public record CircleDetailResponse(
        UUID id,
        String name,
        String description,
        PublicUserResponse creator,
        long memberCount,
        boolean isCreator,
        boolean inviteEnabled,
        Instant createdAt,
        Instant updatedAt,
        UUID avatarFileId,
        String avatarUrl,
        List<Member> members
) {
    /** 成员公开资料、创建者标记及成员关系创建时间，时间为 UTC。 */
    public record Member(PublicUserResponse user, boolean isCreator, Instant joinedAt) {}
}
