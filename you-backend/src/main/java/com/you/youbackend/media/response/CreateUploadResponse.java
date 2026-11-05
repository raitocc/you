package com.you.youbackend.media.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

/**
 * 图片上传地址申请结果。
 */
@Schema(description = "图片上传地址申请结果")
public record CreateUploadResponse(

        @Schema(description = "文件 ID，用于查询上传就绪状态")
        UUID fileId,


        @Schema(
                description = "预签名 PUT 上传地址，有效期 10 分钟",
                format = "uri",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String url
) {
}