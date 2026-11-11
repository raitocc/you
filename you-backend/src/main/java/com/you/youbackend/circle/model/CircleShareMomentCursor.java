package com.you.youbackend.circle.model;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
/** 共享时光瞬间列表的翻页位置，由上一页最后一个瞬间的 UTC 发生时间和瞬间 ID 组成。 */
@Schema(description = "分页边界，两字段同时提供")
public record CircleShareMomentCursor(
        @NotNull @Schema(description = "末项排序时间，UTC") Instant memoryTime,
        @NotNull @Schema(description = "末项 ID", format = "uuid") UUID id) {}
