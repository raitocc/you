package com.you.youbackend.circle.controller;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.circle.request.*;
import com.you.youbackend.circle.response.CircleResponse;
import com.you.youbackend.circle.response.CircleListResponse;
import com.you.youbackend.circle.model.CircleListCursor;
import com.you.youbackend.common.request.*;
import com.you.youbackend.common.response.*;
import com.you.youbackend.user.filter.JwtAuthFilter;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
/** 圈子创建、本人列表、成员详情及创建者编辑入口。 */
@RestController
@RequestMapping("/circles")
@Tag(name = "圈子")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "参数无效或头像不可用 FILE_NOT_USABLE")
@ApiResponse(responseCode = "401", description = "未登录或凭证失效")
@RequiredArgsConstructor
public class CircleController {
    private final CircleService service;

    /** 创建圈子：重复 ID 返回 409，不覆盖已有圈子。 */
    @PostMapping
    @Operation(summary = "创建圈子")
    @ApiResponse(responseCode = "200", description = "圈子创建成功")
    @ApiResponse(responseCode = "409", description = "ID 已被占用")
    public Result<CircleResponse> createCircle(@Valid @RequestBody CreateCircleRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(service.createCircle(request, userId));
    }

    /** 查看本人圈子列表：按当前用户的成员关系倒序浏览圈子 */
    @GetMapping
    @Operation(summary = "查看本人圈子列表")
    @Parameters({
        @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
        @Parameter(name = "cursor.joinedAt", in = ParameterIn.QUERY, description = "成员关系创建时间", schema = @Schema(type = "string", format = "date-time")),
        @Parameter(name = "cursor.id", in = ParameterIn.QUERY, description = "成员关系 ID", schema = @Schema(type = "string", format = "uuid"))
    })
    public Result<ResultPage<CircleListResponse, CircleListCursor>> listMyCircles(
            @Parameter(hidden = true) @PageQuery PageRequest<CircleListCursor> request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 使用认证上下文限定列表用户。
        return Result.ok(service.listMyCircles(userId, request));
    }

    /** 查看圈子详情：不存在、已解散和非成员统一返回 404。 */
    @GetMapping("/{circleId}")
    @Operation(summary = "查看圈子详情")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    public Result<CircleResponse> getCircle(@PathVariable UUID circleId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 访问资格由业务层判断。
        return Result.ok(service.getCircle(circleId, userId));
    }

    /** 编辑圈子信息：创建者全量保存名称、介绍和头像，省略头像表示移除自定义头像。 */
    @PutMapping("/{circleId}")
    @Operation(summary = "编辑圈子信息")
    @ApiResponse(responseCode = "403", description = "有效成员不是创建者")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    public Result<CircleResponse> updateCircle(@PathVariable UUID circleId,
            @Valid @RequestBody UpdateCircleRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 委托事务业务更新并返回当前圈子信息。
        return Result.ok(service.updateCircle(circleId, request, userId));
    }
}
