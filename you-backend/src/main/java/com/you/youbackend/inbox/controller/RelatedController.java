package com.you.youbackend.inbox.controller;

import com.you.youbackend.common.request.PageQuery;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.Result;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.inbox.model.RelatedCursor;
import com.you.youbackend.inbox.response.RelatedResponse;
import com.you.youbackend.inbox.service.RelatedService;
import com.you.youbackend.user.filter.JwtAuthFilter;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Related 读取入口，身份及泛型分页解析沿用统一基础能力。 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "与你有关")
public class RelatedController {
    private final RelatedService relatedService;

    /** 浏览当前可访问的分享动态，卡片通过来源与分享 ID 打开现有共享详情。 */
    @Operation(summary = "浏览与你有关的动态", operationId = "getMomentRelated",
            description = "混合返回瞬间、时光摘要，按分享时间及动态 ID 倒序。摘要字段可按实际业务体验调整。")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "分页参数无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或过期")
    @Parameters({
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页数量，默认 20",
                    schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
            @Parameter(name = "cursor.sharedAt", in = ParameterIn.QUERY, description = "上一页末条动态分享时间，首屏省略",
                    schema = @Schema(type = "string", format = "date-time")),
            @Parameter(name = "cursor.id", in = ParameterIn.QUERY, description = "同一条信箱动态 UUIDv7，与时间成组提供",
                    schema = @Schema(type = "string", format = "uuid"))
    })
    @GetMapping("/moments/related")
    public Result<ResultPage<RelatedResponse, RelatedCursor>> browseRelated(
            @Parameter(hidden = true) @PageQuery PageRequest<RelatedCursor> request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 分页解析器负责请求校验，业务层负责当前可见范围与摘要加载。
        return Result.ok(relatedService.browseRelated(userId, request));
    }
}
