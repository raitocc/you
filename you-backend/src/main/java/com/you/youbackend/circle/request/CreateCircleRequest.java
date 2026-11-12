package com.you.youbackend.circle.request;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.you.youbackend.common.validation.UUIDv7;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;
import java.util.UUID;
public record CreateCircleRequest(
    @NotNull @UUIDv7 @Schema(description = "客户端预生成 UUIDv7，重试保持不变") UUID id,
    @NotBlank @CodePointLength(max = 128)
    @Schema(description = "名称，去首尾空白后 1～128 Unicode 码点") String name,
    @CodePointLength(max = 5000)
    @Schema(description = "介绍，null 清空", nullable = true) String description,
    @Schema(description = "圈子头像", nullable = true) UUID avatarFileId
) {
    /** 在边界校验前整理名称，其他字段保留原始语义。 */
    public CreateCircleRequest {
        // 名称整理后再执行非空及码点长度校验。
        name = name == null ? null : name.strip();
    }
}
