package com.you.youbackend.gathering.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 面对面入口信息，供创建者展示短码与倒计时。
 * @param gatheringId 同行 UUIDv7
 * @param faceCode 当前生效的四位数字短码
 * @param expiresAt 固定截止时间，UTC，重复开启保持原样
 */
@Schema(description = "面对面入口信息")
public record FaceToFaceOpenResponse(
        @Schema(description = "同行 UUIDv7", format = "uuid")
        UUID gatheringId,

        @Schema(description = "当前生效的四位数字短码", pattern = "^[0-9]{4}$")
        String faceCode,

        @Schema(description = "截止时间，UTC，为同行创建后 10 分钟", format = "date-time")
        Instant expiresAt
) {
}