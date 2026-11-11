package com.you.youbackend.circle.controller;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.circle.response.*;
import com.you.youbackend.circle.model.*;
import com.you.youbackend.moment.response.MomentResponse;
import com.you.youbackend.circle.request.*;
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
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/circles")
/** 提供圈子资料和圈子分享的 HTTP 接口。 */
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

    /** 分享到多个圈子 */
    @PostMapping("/shares")
    @Operation(summary = "分享到多个圈子")
    @ApiResponse(responseCode = "403", description = "只能分享本人内容")
    @ApiResponse(responseCode = "404", description = "原内容或目标圈子不存在或不可访问")
    @ApiResponse(responseCode = "409", description = "RESOURCE_CONFLICT 并发分享冲突")
    public Result<Void> share(@Valid @RequestBody CreateCircleSharesRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        service.shareToCircles(request, userId);
        return Result.ok(null);
    }

    /** 撤回指定圈子里的这条分享，有权限时重复撤回也返回成功。 */
    @DeleteMapping("/{circleId}/shares/{shareId}")
    @Operation(summary = "撤回圈子分享")
    @ApiResponse(responseCode = "403", description = "只有分享者或圈子创建者可以撤回")
    @ApiResponse(responseCode = "404", description = "圈子或分享不存在或不可访问")
    public Result<Void> withdraw(@PathVariable UUID circleId, @PathVariable UUID shareId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 只撤回 URL 指定的这条分享，原瞬间或时光仍保留。
        service.withdrawShare(circleId, shareId, userId);
        return Result.ok(null);
    }

    /** 分页浏览圈子分享：查下一页时传上一页最后一条分享的时间和 ID。 */
    @GetMapping("/{circleId}/shares")
    @Operation(summary = "分页浏览圈子分享")
    @ApiResponse(responseCode = "404", description = "圈子或分享不存在或不可访问")
    @Parameters({
        @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
        @Parameter(name = "cursor.sharedAt", in = ParameterIn.QUERY, description = "末项排序时间", schema = @Schema(type = "string", format = "date-time")),
        @Parameter(name = "cursor.id", in = ParameterIn.QUERY, description = "末项 ID", schema = @Schema(type = "string", format = "uuid"))
    })
    public Result<ResultPage<CircleShareFeedResponse, CircleShareCursor>> browse(
            @PathVariable UUID circleId,
            @Parameter(hidden = true) @PageQuery PageRequest<CircleShareCursor> request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(service.browseShares(circleId, userId, request));
    }

    /** 分页浏览圈子共享时光中的瞬间：查下一页时传上一页最后一个瞬间的发生时间和 ID。 */
    @GetMapping("/{circleId}/shares/{shareId}/moments")
    @Operation(summary = "分页浏览圈子共享时光中的瞬间")
    @ApiResponse(responseCode = "404", description = "圈子或分享不存在或不可访问")
    @ApiResponse(responseCode = "409", description = "分享不是时光")
    @Parameters({
        @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
        @Parameter(name = "cursor.memoryTime", in = ParameterIn.QUERY, description = "末项排序时间", schema = @Schema(type = "string", format = "date-time")),
        @Parameter(name = "cursor.id", in = ParameterIn.QUERY, description = "末项 ID", schema = @Schema(type = "string", format = "uuid"))
    })
    public Result<ResultPage<MomentResponse, CircleShareMomentCursor>> timeline(@PathVariable UUID circleId, @PathVariable UUID shareId,
            @Parameter(hidden = true) @PageQuery PageRequest<CircleShareMomentCursor> request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(service.browseSharedAlbumMoments(circleId, shareId, userId, request));
    }
}
