package com.you.youbackend.gathering.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 附近同码同行的候选项，供用户辨认并选择目标。
 * @param gathering 同行摘要，其中的 ID 用于确认加入
 * @param distanceMeters 距离发起位置的米数，列表按距离升序
 * @param expiresAt 入口截止时间，UTC
 * @param isMember 当前用户是否为有效成员
 */
@Schema(description = "面对面同行候选项")
public record FaceToFaceCandidateResponse(
        @Schema(description = "同行摘要，其中的 ID 用于确认加入") FaceToFaceSummaryResponse gathering,
        @Schema(description = "距发起位置的米数，按此值升序排列", minimum = "0", maximum = "100")
        double distanceMeters,
        @Schema(description = "入口截止时间，UTC", format = "date-time") Instant expiresAt,
        @Schema(description = "当前用户是否为有效成员；为 true 时可直接进入") boolean isMember
) {
}
