package com.you.youbackend.moment.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.you.youbackend.moment.model.MomentLocation;
import com.you.youbackend.moment.model.MomentMood;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 全量替换瞬间的可编辑内容，所有字段均须提交；ID 通过路径参数传入。
 * null 清空对应可空字段，空数组清空图片或标签关联；正文与图片仍需至少提供一种。
 *
 * @param title 标题，null 或空白内容清空
 * @param body 普通文本正文，null 清空
 * @param imageFileIds 最终保留的图片 ID，按展示顺序排列；空数组清空关联，原媒体文件保留
 * @param tags 最终保留的标签名称，空数组清空，保存前去首尾空白并整理重复项
 * @param mood 心情，null 清空
 * @param memoryTime 经历实际发生时间，必填，转换为 UTC 毫秒时间入库
 * @param location 地点，null 清空全部地点字段
 */
@Schema(description = "全量修改瞬间请求，所有字段均须提交")
public record UpdateMomentRequest(
        @Schema(description = "标题，null 或空白内容表示清空", maxLength = 255, nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty(required = true)
        @CodePointLength(max = 255, message = "标题不能超过 255 字符")
        String title,

        @Schema(description = "普通文本正文，null 表示清空；与图片至少提供一种，UTF-8 编码最多 65535 字节", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty(required = true)
        String body,

        @Schema(description = "最终图片 ID 列表，须为已就绪的本人图片，按展示顺序排列且不可重复；空数组清空关联，原文件保留",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请提交图片列表")
        List<@NotNull(message = "图片 ID 不能为空") UUID> imageFileIds,

        @Schema(description = "最终标签名称列表，空数组清空；名称去首尾空白，每项最多 64 个 Unicode 码点",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请提交标签列表")
        List<@NotNull(message = "标签不能为 null") String> tags,

        @Schema(description = "心情，使用英文枚举名称，null 表示清空", example = "HAPPY", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty(required = true)
        MomentMood mood,

        @Schema(description = "经历实际发生时间，带时区的 ISO 8601 格式，保存为 UTC 毫秒时间", format = "date-time",
                example = "2026-01-01T08:00:00+08:00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请填写发生时间")
        Instant memoryTime,

        @Schema(description = "地点，null 表示清空经纬度、地点名称和地址", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty(required = true)
        @Valid
        MomentLocation location
) {}
