package com.you.youbackend.moment.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** 瞬间删除结果。 */
@Schema(description = "瞬间删除结果")
public record DeleteMomentResponse(
        @Schema(description = "瞬间 ID") UUID id,
        @Schema(description = "是否已删除", example = "true") boolean deleted
) {
}
