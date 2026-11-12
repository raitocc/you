package com.you.youbackend.circle.request;

import com.you.youbackend.common.util.InviteCodeGenerator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 圈子确认加入请求，使用与同行相同规则的长期字符串邀请码。 */
public record JoinCircleRequest(
        @NotBlank
        @Size(max = InviteCodeGenerator.LENGTH)
        @Schema(description = "系统生成的长期邀请码，最多 16 字符", example = "ABCD1234EFGH5678")
        String inviteCode
) {}
