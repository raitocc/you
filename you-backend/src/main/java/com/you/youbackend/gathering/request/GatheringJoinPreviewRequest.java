package com.you.youbackend.gathering.request;

import com.you.youbackend.gathering.validation.GatheringInviteCode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 邀请码加入预览的查询参数。
 * @param inviteCode 长期邀请码，扫码提取或手动输入，非空白且最多 16 字符
 */
@Schema(description = "同行加入预览查询")
public record GatheringJoinPreviewRequest(
        @Schema(description = "长期邀请码，扫码提取或手动输入", minLength = 1, maxLength = 16,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @GatheringInviteCode
        String inviteCode
) {
}