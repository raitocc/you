package com.you.youbackend.album.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

import java.util.List;
import java.util.UUID;

/**
 * 手动创建时光的请求。
 * @param name 名称，构造时去首尾空白，之后校验 1～128 个 Unicode 码点，允许重名
 * @param description 介绍，最多 5000 个 Unicode 码点；省略或 null 表示未设置
 * @param coverFileId 本人已上传就绪的图片 ID；省略或 null 表示无封面
 * @param momentIds 必填的初始瞬间 ID 数组，允许空数组，重复 ID 合并
 */
@Schema(description = "手动创建时光请求")
public record CreateAlbumRequest(
        @Schema(description = "名称，去首尾空白后 1～128 字符，允许重名", minLength = 1, maxLength = 128,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请填写时光名称")
        @CodePointLength(max = 128, message = "时光名称不能超过 128 字符")
        String name,

        @Schema(description = "介绍，最多 5000 字符；省略或 null 表示未设置", maxLength = 5000, nullable = true)
        @CodePointLength(max = 5000, message = "时光介绍不能超过 5000 字符")
        String description,

        @Schema(description = "本人已就绪的封面图片 ID；省略或 null 表示无封面", format = "uuid", nullable = true)
        UUID coverFileId,

        @Schema(description = "本人未删除瞬间 ID 数组，允许空数组，重复 ID 合并",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请提供初始瞬间 ID 数组")
        List<@NotNull(message = "瞬间 ID 不能为空") UUID> momentIds
) {
    /** 仅整理名称，基础字段校验由 MVC 在构造完成后触发。 */
    public CreateAlbumRequest {
        // 先去除名称首尾空白，使后续非空白及长度校验针对最终保存内容执行。
        name = name == null ? null : name.strip();
    }
}
