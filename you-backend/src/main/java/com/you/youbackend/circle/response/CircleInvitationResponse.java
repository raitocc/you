package com.you.youbackend.circle.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** 圈子邀请状态；关闭时邀请码为 null，开启时返回随机生成的长期字符串。 */
public record CircleInvitationResponse(
        // 邀请开关
        boolean inviteEnabled,
        // 邀请码
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "长期邀请码，关闭时为 null", nullable = true, example = "ABCD1234EFGH5678")
        String inviteCode
) {}
