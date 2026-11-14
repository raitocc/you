package com.you.youbackend.inbox.response;

import com.you.youbackend.user.response.PublicUserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Related 中的时光摘要，内容及数量随原时光更新。 */
@Schema(description = "Related 时光摘要")
public record RelatedAlbumSummaryResponse(
        @Schema(description = "原时光 ID", format = "uuid") UUID id,
        @Schema(description = "时光名称") String name,
        @Schema(description = "介绍前 100 个 Unicode 码点，未设置时为 null", nullable = true) String descriptionPreview,
        @Schema(description = "封面签名地址，无有效封面时为 null", nullable = true, format = "uri") String coverUrl,
        @Schema(description = "当前有效的所有者本人瞬间数量", minimum = "0") long momentCount,
        @Schema(description = "所有者公开资料，查不到时为 null", nullable = true) PublicUserResponse owner
) {}
