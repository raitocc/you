package com.you.youbackend.circle.response;
import com.you.youbackend.user.response.PublicUserResponse;
import java.time.Instant;
import java.util.UUID;
/** 圈子完整展示信息；时间为 UTC，介绍和自定义头像 ID 可空，avatarUrl 始终提供地址。 */
public record CircleResponse(UUID id, String name, String description, PublicUserResponse creator,
        long memberCount, boolean isCreator, boolean inviteEnabled, Instant createdAt, Instant updatedAt,
        UUID avatarFileId, String avatarUrl) {}
