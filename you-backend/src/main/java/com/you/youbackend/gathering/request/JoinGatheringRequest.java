package com.you.youbackend.gathering.request;

import com.you.youbackend.gathering.validation.GatheringInviteCode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用户确认加入同行的请求。
 * @param inviteCode 长期邀请码，非空白且最多 16 字符，按提交时状态重新校验
 */
@Schema(description = "通过邀请码确认加入同行请求")
public record JoinGatheringRequest(
        @Schema(description = "长期邀请码，扫码提取或手动输入", minLength = 1, maxLength = 16,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @GatheringInviteCode
        String inviteCode
) {
}