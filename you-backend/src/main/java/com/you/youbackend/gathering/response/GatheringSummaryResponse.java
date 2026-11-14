package com.you.youbackend.gathering.response;

import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.gathering.model.GatheringStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 本人同行列表摘要。
 *
 * @param status 同行状态：进行中或已结束
 * @param id                 同行 UUIDv7
 * @param name               同行名称
 * @param descriptionPreview 介绍前 100 个 Unicode 码点，未设置为 null
 * @param creator            创建者公开资料，包含 ID、昵称和头像临时签名 URL
 * @param memberCount        有效成员数
 * @param momentCount        有效关联瞬间数
 * @param createdAt          同行创建时间，UTC
 */
@Schema(description = "本人同行列表摘要")
public record GatheringSummaryResponse(
        @Schema(description = "同行状态：ONGOING 进行中，ENDED 已结束")
        GatheringStatus status,
        @Schema(description = "同行 UUIDv7", format = "uuid") UUID id,
        @Schema(description = "同行名称") String name,
        @Schema(description = "介绍前 100 个 Unicode 码点", maxLength = 100, nullable = true) String descriptionPreview,
        @Schema(description = "创建者公开资料，查不到时为 null", nullable = true) PublicUserResponse creator,
        @Schema(description = "有效成员数", minimum = "0") long memberCount,
        @Schema(description = "有效关联瞬间数", minimum = "0") long momentCount,
        @Schema(description = "同行创建时间，UTC", format = "date-time") Instant createdAt
) {
}
