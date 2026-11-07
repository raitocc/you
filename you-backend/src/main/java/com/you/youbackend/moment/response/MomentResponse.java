package com.you.youbackend.moment.response;

import com.you.youbackend.moment.model.MomentLocation;
import com.you.youbackend.moment.model.MomentMood;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 瞬间完整详情，用于详情阅读、编辑回填及发布或修改后的响应。
 *
 * @param id 瞬间 UUIDv7
 * @param title 标题，未设置时为 null
 * @param body 普通文本正文，可为 null
 * @param images 按展示顺序排列的图片，无图片时为空列表
 * @param tags 标签名称，无标签时为空列表
 * @param mood 心情枚举名称，未设置时为 null
 * @param memoryTime 经历实际发生时间，UTC
 * @param location 地点，未设置时为 null
 * @param createdAt 发布时间，UTC
 * @param updatedAt 最后修改时间，UTC
 */
@Schema(description = "瞬间完整详情")
public record MomentResponse(
        @Schema(description = "瞬间 UUIDv7", format = "uuid")
        UUID id,

        @Schema(description = "标题，未设置时为 null", nullable = true)
        String title,

        @Schema(description = "普通文本正文，可为 null", nullable = true)
        String body,

        @Schema(description = "按展示顺序排列的图片，无图片时为空列表")
        List<Image> images,

        @Schema(description = "标签名称，无标签时为空列表")
        List<String> tags,

        @Schema(description = "心情，使用英文枚举名称，未设置时为 null", example = "HAPPY", nullable = true)
        MomentMood mood,

        @Schema(description = "经历实际发生时间，UTC", format = "date-time", example = "2026-01-01T00:00:00Z")
        Instant memoryTime,

        @Schema(description = "地点，未设置时为 null", nullable = true)
        MomentLocation location,

        @Schema(description = "发布时间，UTC，与经历实际发生时间区分", format = "date-time")
        Instant createdAt,

        @Schema(description = "最后修改时间，UTC", format = "date-time")
        Instant updatedAt
) {
    /**
     * 瞬间中的单张图片，排列位置由外层 images 列表顺序决定。
     *
     * @param fileId 媒体文件 UUID，与瞬间 ID、图片关联 ID 区分
     * @param url 有效期 10 分钟的 GET 签名地址，在响应时生成
     */
    @Schema(description = "瞬间图片")
    public record Image(
            @Schema(description = "媒体文件 ID", format = "uuid")
            UUID fileId,

            @Schema(description = "图片临时读取地址，有效期 10 分钟", format = "uri")
            String url
    ) {}
}
