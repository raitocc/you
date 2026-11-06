package com.you.youbackend.common.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 统一游标分页请求，用于绑定列表接口的查询参数。
 */
@Data
@Schema(description = "统一游标分页请求")
public class PageRequest {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    /**
     * 上一页返回的游标，首屏或刷新时省略。
     */
    @Schema(description = "上一页返回的 nextCursor，首屏或刷新时省略")
    @Pattern(regexp = "(?s).*\\S.*", message = "分页游标不能为空白")
    private String cursor;

    /**
     * 每页最多返回的记录数。
     */
    @Schema(description = "每页最多返回的记录数", defaultValue = "20", example = "20")
    @NotNull(message = "每页条数不能为空")
    @Min(value = 1, message = "每页条数至少为 {value}")
    @Max(value = MAX_LIMIT, message = "每页条数最多为 {value}")
    private Integer limit = DEFAULT_LIMIT;
}
