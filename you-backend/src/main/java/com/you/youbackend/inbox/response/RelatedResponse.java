package com.you.youbackend.inbox.response;

import com.you.youbackend.inbox.model.InboxObjectType;
import com.you.youbackend.moment.response.MomentSummaryResponse;
import com.you.youbackend.user.response.PublicUserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 一次分享行为的一张卡片，moment 与 album 按 objectType 恰好提供一种。 */
@Schema(description = "Related 动态卡片，摘要字段可按实际卡片体验调整")
public record RelatedResponse(
        @Schema(description = "信箱动态 UUIDv7，用于卡片标识和分页", format = "uuid") UUID id,
        @Schema(description = "本次关联或分享时间，UTC；动态排序依据") Instant sharedAt,
        @Schema(description = "内容类型，moment 或 album") InboxObjectType objectType,
        @Schema(description = "来源空间") RelatedSourceResponse source,
        @Schema(description = "圈子分享关系 ID，同行动态为 null", nullable = true, format = "uuid") UUID shareId,
        @Schema(description = "分享者公开资料，查不到时为 null", nullable = true) PublicUserResponse actor,
        @Schema(description = "瞬间摘要，时光卡片为 null", nullable = true) MomentSummaryResponse moment,
        @Schema(description = "时光摘要，瞬间卡片为 null", nullable = true) RelatedAlbumSummaryResponse album
) {}
