package com.you.youbackend.user.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户登录请求。
 */
@Schema(description = "用户登录请求")
public record LoginRequest(

        @Schema(description = "登录账号，忽略大小写", example = "calmer")
        @NotNull(message = "请填写用户名")
        @Pattern(
                regexp = "[A-Za-z][A-Za-z0-9_]{3,63}",
                message = "用户名格式不正确")
        String username,

        @Schema(description = "登录密码", example = "aaaa1111",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotNull(message = "请填写密码")
        @Size(min = 8, max = 64, message = "密码长度须为8～64字符")
        String password
) {
}