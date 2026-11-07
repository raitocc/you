package com.you.youbackend.moment.response;

import com.you.youbackend.moment.model.MomentMood;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 本人瞬间流中的卡片摘要，完整正文及图片列表通过详情接口读取。
 *
 * @param id 瞬间 UUIDv7，用于打开详情
 * @param title 标题，未设置时为 null
 * @param bodyPreview 正文开头最多 100 个 Unicode 码点，正文为空时为 null
 * @param coverUrl 按展示顺序选取的首张有效图片的临时读取地址，无有效图片时为 null
 * @param imageCount 当前有效图片数量
 * @param mood 心情枚举名称，未设置时为 null
 * @param memoryTime 经历实际发生时间，UTC，用于时间展示和排序
 */
@Schema(description = "本人瞬间流卡片摘要")
public record MomentFeedItem(
        @Schema(description = "瞬间 UUIDv7", format = "uuid")
        UUID id,

        @Schema(description = "标题，未设置时为 null", nullable = true)
        String title,

        @Schema(description = "正文开头最多 100 个 Unicode 码点，正文为空时为 null", maxLength = 100, nullable = true)
        String bodyPreview,

        @Schema(description = "首张有效图片的临时读取地址，有效期 10 分钟；无有效图片时为 null", format = "uri", nullable = true)
        String coverUrl,

        @Schema(description = "当前有效图片数量", minimum = "0")
        int imageCount,

        @Schema(description = "心情，使用英文枚举名称，未设置时为 null", example = "HAPPY", nullable = true)
        MomentMood mood,

        @Schema(description = "经历实际发生时间，UTC", format = "date-time", example = "2026-01-01T00:00:00Z")
        Instant memoryTime
) {}
