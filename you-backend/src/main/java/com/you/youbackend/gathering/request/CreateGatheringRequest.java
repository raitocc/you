package com.you.youbackend.gathering.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * 创建同行的请求，身份与邀请码由服务端确定。
 * @param name 名称，去首尾空白后 1～128 个 Unicode 码点，允许重名
 * @param description 介绍，最多 5000 个 Unicode 码点；省略或 null 表示未设置
 */
@Schema(description = "创建同行请求")
public record CreateGatheringRequest(
        @Schema(description = "名称，去首尾空白后 1～128 个 Unicode 码点，允许重名",
                minLength = 1, maxLength = 128, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请填写同行名称")
        @CodePointLength(max = 128, message = "同行名称不能超过 128 字符")
        String name,

        @Schema(description = "介绍，最多 5000 个 Unicode 码点；省略或 null 表示未设置",
                maxLength = 5000, nullable = true)
        @CodePointLength(max = 5000, message = "同行介绍不能超过 5000 字符")
        String description
) {
    /** 整理名称，使 MVC 字段校验针对最终保存内容执行。 */
    public CreateGatheringRequest {
        // 去除名称首尾空白，介绍保留用户原文。
        name = (name == null) ? null : name.strip();
    }
}