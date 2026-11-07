package com.you.youbackend.common.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 通用游标分页请求，C 为各业务自己的游标类型。
 *
 * <p>首屏省略 cursor 或传 null；后续将上次响应的 nextCursor 对象原样传回。
 * Controller 通过 @Valid 触发 limit 校验，并级联校验具体游标中的字段。</p>
 *
 * @param cursor 分页边界，null 表示首屏
 * @param limit 每页数量，省略或传 null 时使用默认值 20，范围 1～100
 * @param <C> 业务游标类型，例如 MomentFeedCursor
 */
@Schema(description = "通用游标分页请求")
public record PageRequest<C>(
        @Valid
        @Schema(description = "上次响应的 nextCursor 对象；首屏省略或传 null", nullable = true)
        C cursor,

        @Min(value = 1, message = "每页数量至少为 {value}")
        @Max(value = MAX_LIMIT, message = "每页数量不能超过 {value}")
        @Schema(description = "每页数量，默认 20", example = "20", minimum = "1", maximum = "100")
        Integer limit
) {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    /** JSON 未提供数量或显式传 null 时，在构造请求对象时统一补上默认值。 */
    public PageRequest {
        limit = (limit == null) ? DEFAULT_LIMIT : limit;
    }
}
