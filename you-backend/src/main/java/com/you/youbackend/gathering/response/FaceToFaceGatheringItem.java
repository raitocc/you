package com.you.youbackend.gathering.response;

import com.you.youbackend.user.response.PublicUserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 附近同码同行的候选项，供用户辨认并选择目标。
 * @param id 同行 UUIDv7，确认加入时提交此 ID
 * @param name 同行名称
 * @param description 同行介绍，未设置为 null
 * @param creator 创建者公开资料
 * @param distanceMeters 距离发起位置的米数，列表按距离升序
 * @param expiresAt 入口截止时间，UTC
 * @param isMember 当前用户是否为有效成员
 */
@Schema(description = "面对面同行候选项")
public record FaceToFaceGatheringItem(
        @Schema(description = "同行 UUIDv7，确认加入时提交", format = "uuid") UUID id,
        @Schema(description = "同行名称") String name,
        @Schema(description = "同行介绍，未设置为 null", nullable = true) String description,
        @Schema(description = "创建者公开资料") PublicUserResponse creator,
        @Schema(description = "距发起位置的米数，按此值升序排列", minimum = "0", maximum = "100")
        double distanceMeters,
        @Schema(description = "入口截止时间，UTC", format = "date-time") Instant expiresAt,
        @Schema(description = "当前用户是否为有效成员；为 true 时可直接进入") boolean isMember
) {
}