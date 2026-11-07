package com.you.youbackend.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 统一封装游标分页数据，作为接口响应的 data。
 * TODO：改为 ResultPage<T, C>，将 nextCursor 从 String 改为泛型 C，与统一分页请求 PageRequest<C> 对齐。
 */
@Schema(description = "游标分页结果")
public record ResultPage<T>(
        @Schema(description = "本页数据，无记录时为空列表")
        List<T> items,
        @Schema(description = "下一页游标，最后一页为空", nullable = true)
        String nextCursor,
        @Schema(description = "是否还有下一页")
        boolean hasMore
) {}
