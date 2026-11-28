package com.you.youbackend.circle.response;

import com.you.youbackend.user.response.PublicUserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 圈子详情，包含创建者公开资料和按加入时间排列的全部有效成员。
 * @param id 圈子 UUIDv7
 * @param name 圈子名称
 * @param description 介绍；未设置为 null
 * @param creator 创建者公开资料，查不到时为 null
 * @param memberCount 有效成员数量
 * @param isCreator 当前用户是否为创建者
 * @param inviteEnabled 邀请开关
 * @param createdAt 创建时间，UTC
 * @param updatedAt 最后修改时间，UTC
 * @param avatarFileId 自定义头像图片 ID；未设置为 null
 * @param avatarUrl 头像读取签名地址；未设置或失效时为默认头像地址
 * @param members 有效成员列表，按加入时间正序
 */
@Schema(description = "圈子详情")
@Builder
public record CircleDetailedResponse(
        @Schema(description = "圈子 UUIDv7", format = "uuid")
        UUID id,

        @Schema(description = "圈子名称")
        String name,

        @Schema(description = "介绍；未设置为 null", nullable = true)
        String description,

        @Schema(description = "创建者公开资料，查不到时为 null", nullable = true)
        PublicUserResponse creator,

        @Schema(description = "有效成员数量", minimum = "1")
        long memberCount,

        @Schema(description = "当前用户是否为创建者")
        boolean isCreator,

        @Schema(description = "邀请开关")
        boolean inviteEnabled,

        @Schema(description = "创建时间，UTC", format = "date-time")
        Instant createdAt,

        @Schema(description = "最后修改时间，UTC", format = "date-time")
        Instant updatedAt,

        @Schema(description = "自定义头像图片 ID；未设置为 null", nullable = true)
        UUID avatarFileId,

        @Schema(description = "头像读取签名地址；未设置或失效时为默认头像地址", format = "uri")
        String avatarUrl,

        @Schema(description = "有效成员列表，按加入时间正序")
        List<Member> members
) {
    /**
     * 详情页里的一个成员条目。
     * @param user 成员公开资料
     * @param isCreator 该成员是否为创建者
     * @param joinedAt 加入时间，UTC
     */
    @Schema(description = "圈子成员条目")
    public record Member(
            @Schema(description = "成员公开资料")
            PublicUserResponse user,
            @Schema(description = "该成员是否为创建者")
            boolean isCreator,
            @Schema(description = "加入时间，UTC", format = "date-time")
            Instant joinedAt) {}
}
