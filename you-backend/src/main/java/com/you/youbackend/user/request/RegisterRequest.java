package com.you.youbackend.user.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户注册请求。
 */
@Schema(description = "用户注册请求")
public record RegisterRequest(

        @Schema(description = "登录账号，首位为字母，保存时统一转小写",
                example = "alice_2026")
        @NotNull(message = "请填写用户名")
        @Pattern(
                regexp = "[A-Za-z][A-Za-z0-9_]{3,63}",
                message = "用户名须为4～64位字母、数字或下划线，且首位为字母")
        String username,

        @Schema(description = "密码，8～64字符，至少包含字母和数字",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotNull(message = "请填写密码")
        @Size(min = 8, max = 64, message = "密码长度须为8～64字符")
        @Pattern(
                regexp = "(?s)(?=.*[A-Za-z])(?=.*[0-9]).*",
                message = "密码须至少包含一个英文字母和一个数字")
        String password,

        @Schema(description = "昵称，去除首尾空白后为1～64字符",
                example = "小橘")
        @NotBlank(message = "请填写昵称")
        @Size(max = 64, message = "昵称最多为64字符")
        String nickname
) {

    public RegisterRequest {
        if (nickname != null) {
            nickname = nickname.strip();
        }
    }
}