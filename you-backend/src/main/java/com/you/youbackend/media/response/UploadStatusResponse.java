package com.you.youbackend.media.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 图片上传就绪状态。
 */
@Schema(description = "图片上传就绪状态")
public record UploadStatusResponse(

        @Schema(description = "查询的文件 ID")
        UUID fileId,

        @Schema(description = "文件是否已登记、有效且属于当前用户")
        boolean ready
) {
}