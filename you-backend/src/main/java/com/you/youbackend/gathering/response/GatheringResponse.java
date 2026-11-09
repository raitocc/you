package com.you.youbackend.gathering.response;

import com.you.youbackend.user.response.PublicUserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 同行信息，创建后可直接用于详情展示和邀请。
 * @param id 同行 UUIDv7
 * @param name 名称，去首尾空白后 1～128 个 Unicode 码点
 * @param description 介绍；未设置为 null
 * @param creator 创建者公开资料
 * @param memberCount 有效成员数量，创建时为 1
 * @param momentCount 有效关联瞬间数量，创建时为 0
 * @param inviteCode 系统生成的长期邀请码
 * @param createdAt 创建时间，UTC
 * @param updatedAt 最后修改时间，UTC
 */
@Schema(description = "同行信息")
public record GatheringResponse(
        @Schema(description = "同行 UUIDv7", format = "uuid")
        UUID id,

        @Schema(description = "名称，去首尾空白后 1～128 个 Unicode 码点", minLength = 1, maxLength = 128)
        String name,

        @Schema(description = "介绍；未设置为 null", maxLength = 5000, nullable = true)
        String description,

        @Schema(description = "创建者公开资料")
        PublicUserResponse creator,

        @Schema(description = "有效成员数量", minimum = "1")
        long memberCount,

        @Schema(description = "有效关联瞬间数量", minimum = "0")
        long momentCount,

        @Schema(description = "长期邀请码，可用于生成加入二维码", minLength = 16, maxLength = 16)
        String inviteCode,

        @Schema(description = "创建时间，UTC", format = "date-time")
        Instant createdAt,

        @Schema(description = "最后修改时间，UTC", format = "date-time")
        Instant updatedAt
) {
}