package com.you.youbackend.circle.request;

import jakarta.validation.constraints.NotNull;

/** 圈子邀请开关请求；目标状态必填，false 表示关闭邀请。 */
public record UpdateCircleInvitationRequest(
        @NotNull Boolean inviteEnabled
) {}
