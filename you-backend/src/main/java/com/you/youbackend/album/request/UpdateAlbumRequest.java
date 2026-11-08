package com.you.youbackend.album.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

import java.util.UUID;

/**
 * 全量修改时光展示信息，三个字段均须提交，不修改瞬间关联。
 * @param name 去首尾空白后 1～128 个 Unicode 码点，允许重名
 * @param description 介绍，最多 5000 个 Unicode 码点，null 清空
 * @param coverFileId 本人已就绪图片 ID，null 清空封面
 */
@Schema(description = "全量修改时光信息请求，所有字段均须提交")
public record UpdateAlbumRequest(
        @Schema(description = "名称，去首尾空白后 1～128 字符，允许重名", minLength = 1, maxLength = 128,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请填写时光名称")
        @CodePointLength(max = 128, message = "时光名称不能超过 128 字符")
        String name,

        @Schema(description = "介绍，最多 5000 字符，null 表示清空", maxLength = 5000, nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty(required = true)
        @CodePointLength(max = 5000, message = "时光介绍不能超过 5000 字符")
        String description,

        @Schema(description = "本人已就绪封面图片 ID，null 表示清空", format = "uuid", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty(required = true)
        UUID coverFileId
) {
    /** 先整理名称，再由 MVC 校验最终保存的字符长度和非空白约束。 */
    public UpdateAlbumRequest {
        // 名称沿用创建时的首尾空白整理规则，可空字段保留原始清空语义。
        name = name == null ? null : name.strip();
    }
}
