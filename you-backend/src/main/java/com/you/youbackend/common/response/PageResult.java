package com.you.youbackend.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 统一游标分页结果，作为 Result 的 data 返回。
 *
 * @param items 本页记录，无记录时为空列表
 * @param nextCursor 下一页游标，已到底时为 null
 * @param hasMore 是否还有下一页
 * @param <T> 记录类型
 */
@Schema(description = "统一游标分页结果")
public record PageResult<T>(

        @Schema(description = "本页记录，无记录时为空列表")
        List<T> items,

        @Schema(description = "下一页游标，已到底时为 null", nullable = true)
        String nextCursor,

        @Schema(description = "是否还有下一页", example = "false")
        boolean hasMore
) {

    /**
     * 创建分页结果，根据下一页游标确定是否还有下一页。
     *
     * @param items 本页记录
     * @param nextCursor 下一页游标，已到底时传 null
     */
    public PageResult(List<T> items, String nextCursor) {
        this(items, nextCursor, nextCursor != null);
    }
}
