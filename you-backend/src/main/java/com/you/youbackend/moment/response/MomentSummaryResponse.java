package com.you.youbackend.moment.response;

import com.you.youbackend.moment.model.MomentMood;
import com.you.youbackend.user.response.PublicUserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * 瞬间列表中的卡片摘要，完整正文及图片列表通过详情接口读取。
 *
 * @param author 作者公开资料，查不到时为 null
 * @param id 瞬间 UUIDv7，用于打开详情
 * @param title 标题，未设置时为 null
 * @param bodyPreview 正文开头最多 100 个 Unicode 码点，正文为空时为 null
 * @param coverUrl 按展示顺序选取的首张有效图片的临时读取地址，无有效图片时为 null
 * @param imageCount 当前有效图片数量
 * @param mood 心情枚举名称，未设置时为 null
 * @param memoryTime 经历实际发生时间，UTC，用于时间展示和排序
 */
@Schema(description = "瞬间卡片摘要")
@Builder
public record MomentSummaryResponse(
        @Schema(description = "瞬间 ID", format = "uuid")
        UUID id,

        @Schema(description = "瞬间标题", nullable = true)
        String title,

        @Schema(description = "瞬间正文预览", maxLength = 100, nullable = true)
        String bodyPreview,

        @Schema(description = "瞬间封面 url", format = "uri", nullable = true)
        String coverUrl,

        @Schema(description = "瞬间有效图片数量", minimum = "0")
        int imageCount,

        @Schema(description = "瞬间心情", example = "HAPPY", nullable = true)
        MomentMood mood,

        @Schema(description = "瞬间实际发生时间", format = "date-time", example = "2026-01-01T00:00:00Z")
        Instant memoryTime,

        @Schema(description = "瞬间作者公开资料", nullable = true)
        PublicUserResponse author
) {}
