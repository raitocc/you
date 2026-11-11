package com.you.youbackend.circle.model;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
/** 圈子分享列表的翻页位置，由上一页最后一条分享的 UTC 分享时间和分享 ID 组成。 */
@Schema(description = "分页边界，两字段同时提供")
public record CircleShareCursor(
        @NotNull @Schema(description = "末项排序时间，UTC") Instant sharedAt,
        @NotNull @Schema(description = "末项 ID", format = "uuid") UUID id
) {}
