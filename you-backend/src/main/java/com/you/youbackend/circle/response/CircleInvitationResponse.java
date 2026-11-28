package com.you.youbackend.circle.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 圈子邀请状态。
 * @param inviteEnabled 邀请开关，true 表示可用邀请码加入
 * @param inviteCode 当前邀请码；关闭邀请时为 null，开启时为系统生成的长期字符串
 */
public record CircleInvitationResponse(
        boolean inviteEnabled,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "长期邀请码，关闭时为 null", nullable = true, example = "ABCD1234EFGH5678")
        String inviteCode
) {}
