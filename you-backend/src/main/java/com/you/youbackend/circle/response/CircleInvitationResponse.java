package com.you.youbackend.circle.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** 圈子邀请状态；关闭时邀请码为 null，开启时返回从 10000 起分配的整数。 */
public record CircleInvitationResponse(
        // 邀请开关
        boolean inviteEnabled,
        // 邀请码
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "自增数字邀请码，关闭时为 null", nullable = true, minimum = "10000", example = "10000")
        Long inviteCode
) {}
