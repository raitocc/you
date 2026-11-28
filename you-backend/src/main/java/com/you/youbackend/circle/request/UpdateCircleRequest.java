package com.you.youbackend.circle.request;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.you.youbackend.common.validation.UUIDv7;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;
import java.util.UUID;
/**
 * 全量修改圈子展示信息的请求。
 * @param name 名称，去首尾空白后 1～128 个 Unicode 码点
 * @param description 介绍，必须提交，null 清空，最多 5000 个 Unicode 码点
 * @param avatarFileId 本人有效图片 ID，null 清空自定义头像（展示时回落默认头像）
 */
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
