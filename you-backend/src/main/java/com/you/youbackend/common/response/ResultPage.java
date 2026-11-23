package com.you.youbackend.common.response;

import com.you.youbackend.moment.model.MomentFeedCursor;
import com.you.youbackend.moment.response.MomentSummaryResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.function.Function;

/**
 * 统一封装游标分页数据，作为接口响应的 data。
 *
 * @param <T> 本页条目类型
 * @param <C> 与 PageRequest 对应的业务游标类型
 */
@Schema(description = "游标分页结果")
public record ResultPage<T, C>(
        @Schema(description = "本页数据，无记录时为空列表")
        List<T> items,
        @Schema(description = "下一页游标，最后一页为空", nullable = true)
        C nextCursor,
        @Schema(description = "是否还有下一页")
        boolean hasMore
) {
    /** 空页结果：没有数据、没有下一页。 */
    public static <T, C> ResultPage<T, C> empty() {
        return new ResultPage<>(List.of(), null, false);
    }

    /**
     * 用本页最后一条生成下一页游标，拼成分页结果。
     *
     * @param items        本页数据，排列顺序就是展示顺序
     * @param hasMore      多查一条得出的“还有下一页”
     * @param cursorMapper 从条目取游标字段
     * @return 分页结果；hasMore 为 false 时 nextCursor 为 null
     */
    // TODO 可以使用接口，或者直接new，不需要用函数
    public static <T, C> ResultPage<T, C> toResultPage(
            List<T> items,
            boolean hasMore,
            Function<T, C> cursorMapper) {

        C nextCursor = hasMore
                ? cursorMapper.apply(items.get(items.size() - 1))
                : null;

        return new ResultPage<>(items, nextCursor, hasMore);
    }

}
