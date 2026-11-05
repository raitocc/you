package com.you.youbackend.user.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 全量更新本人可编辑资料。
 */
@Schema(description = "更新本人资料的请求")
public record UpdateProfileRequest(

        @Schema(description = "昵称，去除首尾空白后为1～64字符", example = "小橘")
        @NotBlank(message = "请填写昵称")
        @Size(max = 64, message = "昵称最多为64字符")
        String nickname,

        @Schema(description = "已上传的本人图片文件 ID，null 表示移除头像",
                example = "01a1aa57-1bee-798d-ae11-3f508eb9c37d")
        UUID avatarFileId
) {

    public UpdateProfileRequest {
        if (nickname != null) {
            nickname = nickname.strip();
        }
    }
}