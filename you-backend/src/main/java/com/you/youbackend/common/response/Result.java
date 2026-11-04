package com.you.youbackend.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一接口响应。
 *
 * @param code 业务结果码
 * @param message 结果说明
 * @param data 接口返回的数据
 * @param <T> 数据类型
 */
@Schema(description = "统一接口响应")
public record Result<T>(

        @Schema(description = "业务结果码", example = "SUCCESS")
        String code,

        @Schema(description = "结果说明", example = "操作成功")
        String message,

        @Schema(description = "接口返回的数据")
        T data
) {

    /**
     * 创建成功响应。
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>("SUCCESS", "操作成功", data);
    }

    /**
     * 创建失败响应，数据为空。
     *
     * @param code 业务错误码
     * @param message 错误说明
     * @return 失败响应
     */
    public static Result<Void> fail(String code, String message) {
        return new Result<>(code, message, null);
    }
}