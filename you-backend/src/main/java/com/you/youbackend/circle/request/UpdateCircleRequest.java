package com.you.youbackend.circle.request;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.you.youbackend.common.validation.UUIDv7;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;
import java.util.UUID;
/** 圈子请求；名称去首尾空白，介绍最多 5000 Unicode 码点，头像省略或 null 使用默认头像。 */
public record UpdateCircleRequest(
    @NotBlank @CodePointLength(max = 128)
    @Schema(description = "名称，去首尾空白后 1～128 Unicode 码点") String name,
    @JsonProperty(required = true)
    @CodePointLength(max = 5000)
    @Schema(description = "介绍，null 清空", nullable = true) String description,
    @Schema(description = "本人有效图片 ID，省略或 null 移除自定义头像", nullable = true) UUID avatarFileId
) {
    /** 在边界校验前整理名称，其他字段保留原始语义。 */
    public UpdateCircleRequest {
        // 名称整理后再执行非空及码点长度校验。
        name = name == null ? null : name.strip();
    }
}
