package com.you.youbackend.moment.request;

import com.you.youbackend.common.validation.UUIDv7;
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
 * 发布瞬间的请求；客户端草稿生成 ID，图片直传并就绪后提交关联。
 * 正文与图片至少提供一种，发生时间与实际发布时间分别记录。
 *
 * @param id 客户端生成的 UUIDv7，允许历史草稿 ID，最多超前服务端 5 分钟
 * @param title 标题，可省略或为 null，空白标题保存为 null
 * @param body 普通文本正文，可省略或为 null；无有效正文时须提供图片
 * @param imageFileIds 已上传并就绪的本人图片 ID，按展示顺序排列，省略、null 或空数组表示无图片
 * @param tags 标签名称列表，省略、null 或空数组表示无标签，保存前去首尾空白并整理重复项
 * @param mood 心情，省略或 null 表示未设置
 * @param memoryTime 经历实际发生时间，接收带时区的 ISO 8601 时间，保存为 UTC 毫秒时间
 * @param location 地点，省略或 null 表示未设置
 * @param gatheringId 可选同行 UUIDv7，仅允许关联本人已加入且进行中的同行
 */
@Schema(description = "发布瞬间请求，正文与图片至少提供一种")
public record CreateMomentRequest(
        @Schema(format = "uuid", description = "客户端生成的 UUIDv7，最多允许超前服务端 5 分钟",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请填写瞬间 ID")
        @UUIDv7
        UUID id,

        @Schema(description = "标题，可省略或为 null，空白内容保存为 null", maxLength = 255, nullable = true)
        @CodePointLength(max = 255, message = "标题不能超过 255 字符")
        String title,

        @Schema(description = "普通文本正文，可省略或为 null；与图片至少提供一种，UTF-8 编码最多 65535 字节", nullable = true)
        String body,

        @Schema(description = "已就绪的本人图片 ID，按展示顺序排列且不可重复；省略、null 或空数组表示无图片", nullable = true)
        List<@NotNull(message = "图片 ID 不能为空") UUID> imageFileIds,

        @Schema(description = "标签名称列表，省略、null 或空数组表示无标签；名称去首尾空白，每项最多 64 个 Unicode 码点", nullable = true)
        List<@NotNull(message = "标签不能为 null") String> tags,

        @Schema(description = "心情，使用英文枚举名称，省略或 null 表示未设置", example = "HAPPY", nullable = true)
        MomentMood mood,

        @Schema(format = "date-time", description = "经历实际发生时间，带时区的 ISO 8601 格式，保存为 UTC 毫秒时间",
                example = "2026-01-01T08:00:00+08:00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请填写发生时间")
        Instant memoryTime,

        @Schema(description = "地点，省略或 null 表示未设置", nullable = true)
        @Valid
        MomentLocation location,

        @Schema(description = "可选同行 UUIDv7；须为本人已加入且进行中的有效同行，省略或 null 保持私人发布",
                format = "uuid", nullable = true)
        @UUIDv7(checkTimestamp = false)
        UUID gatheringId

) {}
