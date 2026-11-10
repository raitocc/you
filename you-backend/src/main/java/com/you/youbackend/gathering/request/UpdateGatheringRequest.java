package com.you.youbackend.gathering.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 全量修改同行基本信息。
 * @param name 名称，去首尾空白后 1～128 个 Unicode 码点
 * @param description 介绍，必须提交，null 清空，最多 5000 个 Unicode 码点
 */
@Schema(description = "全量修改同行基本信息请求")
public record UpdateGatheringRequest(
        @NotBlank(message = "请填写同行名称")
        @CodePointLength(max = 128, message = "同行名称不能超过 128 字符")
        @Schema(description = "名称，去首尾空白后 1～128 个 Unicode 码点", minLength = 1, maxLength = 128,
                requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @JsonProperty(required = true)
        @CodePointLength(max = 5000, message = "同行介绍不能超过 5000 字符")
        @Schema(description = "介绍，null 清空", maxLength = 5000, nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED) String description
) {
    /** 先整理名称，使字段校验针对最终保存的内容执行。 */
    public UpdateGatheringRequest {
        // 名称去首尾空白，介绍保留原文及显式清空语义。
        name = name == null ? null : name.strip();
    }
}
