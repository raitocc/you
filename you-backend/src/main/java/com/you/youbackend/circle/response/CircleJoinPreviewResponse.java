package com.you.youbackend.circle.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.you.youbackend.user.response.PublicUserResponse;
import lombok.Builder;
import java.util.UUID;

/** 圈子加入预览；包含圈子基本资料及当前用户是否为有效成员。 */
public record CircleJoinPreviewResponse(
        CircleSummary circle,
        @JsonProperty("isMember") boolean isMember
) {
    /** 圈子加入前可展示的基本资料；介绍和自定义头像 ID 可空，avatarUrl 始终提供地址。 */
    @Builder
    public record CircleSummary(
            UUID id,
            String name,
            String description,
            PublicUserResponse creator,
            UUID avatarFileId,
            String avatarUrl
    ) {}
}
