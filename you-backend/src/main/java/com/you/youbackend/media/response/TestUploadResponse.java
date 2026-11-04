package com.you.youbackend.media.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 通过预签名地址上传文件的结果。
 *
 * @param statusCode RustFS 返回的 HTTP 状态码
 * @param etag RustFS 返回的对象实体标签
 */
@Schema(description = "预签名上传测试结果")
public record TestUploadResponse(
        @Schema(description = "RustFS 返回的 HTTP 状态码", example = "200")
        int statusCode,
        @Schema(description = "RustFS 返回的对象实体标签")
        String etag
) {
}
