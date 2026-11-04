package com.you.youbackend.media.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 申请文件上传地址的请求。
 */
@Data
@Schema(description = "申请图片上传地址的请求")
public class CreateUploadRequest {


    private static final long MAX_UPLOAD_BYTES = 10 * 1024 * 1024L;

    /**
     * 声明的文件字节数，范围为 1 字节至 10 MiB。
     */
    @Schema(description = "文件字节数，最大 10 MiB", example = "1000000")
    @NotNull(message = "请提供文件大小")
    @Positive(message = "文件大小必须大于 0")
    @Max(value = MAX_UPLOAD_BYTES, message = "文件大小最多为 {value} 字节")
    private Long byteSize;

    /**
     * 声明的图片 MIME 类型。
     */
    @Schema(description = "图片 MIME 类型，支持 PNG、JPEG、WebP", example = "image/png")
    @NotNull(message = "请选择图片类型")
    private MimeType mimeType;

    public enum MimeType {
        IMAGE_WEBP,
        IMAGE_JPEG,
        IMAGE_PNG;

        @Override
        @JsonValue
        public String toString() {
            return switch (this) {
                case IMAGE_WEBP -> "image/webp";
                case IMAGE_JPEG -> "image/jpeg";
                case IMAGE_PNG -> "image/png";
            };
        }


    }
}