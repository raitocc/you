package com.you.youbackend.circle.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.media.Schema;

/** 圈子加入预览请求；邀请码必填，为大于等于 10000 的整数。 */
public record CircleJoinPreviewRequest(
        @NotNull @Min(10000)
        @Schema(description = "自增数字邀请码", example = "10000") Long inviteCode
) {}
