package com.you.youbackend.moment.model;

import com.you.youbackend.common.validation.UUIDv7;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * 瞬间流的分页边界，保存上一页最后一条瞬间的发生时间和 ID。
 *
 * <p>瞬间按发生时间、ID 双字段倒序排列。发生时间相同时，唯一 ID 决定先后顺序；
 * 在数据未变动时排序一致。客户端将 nextCursor 对象原样传回即可，无需编码或解码。
 * 用户修改发生时间会改变排序位置，各次分页查询不提供跨请求的固定快照。</p>
 *
 * @param memoryTime 上一页最后一条瞬间的发生时间，UTC 时间点
 * @param id 同一条瞬间的 UUIDv7，用于区分相同发生时间的记录
 */
@Schema(description = "瞬间流游标：上一页最后一条记录的发生时间和 ID")
public record MomentFeedCursor(
        @NotNull(message = "请提供游标发生时间")
        @Schema(description = "上一页最后一条瞬间的发生时间", example = "2026-01-01T00:00:00Z")
        Instant memoryTime,

        @NotNull(message = "请提供游标瞬间 ID")
        @UUIDv7(checkTimestamp = false)
        @Schema(description = "上一页最后一条瞬间的 UUIDv7", format = "uuid")
        UUID id
) {}
