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
@Tag(name = "圈子")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "参数无效或头像不可用")
@ApiResponse(responseCode = "401", description = "未登录或凭证失效")
@RequiredArgsConstructor
public class CircleController {
    private final CircleService service;

    /** 创建圈子：重复 ID 返回 409，不覆盖已有圈子。 */
    @PostMapping
    @Operation(summary = "创建圈子")
    @ApiResponse(responseCode = "200", description = "圈子创建成功")
    @ApiResponse(responseCode = "409", description = "ID 已被占用")
    public Result<Void> createCircle(
            @Valid @RequestBody CreateCircleRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        service.createCircle(request, userId);
        return Result.ok(null);
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
    @Operation(summary = "查看圈子详情", description = "返回创建者公开资料及全部有效成员")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    public Result<CircleDetailResponse> getCircle(
            @PathVariable UUID circleId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 访问资格由业务层判断。
        return Result.ok(service.getCircle(circleId, userId));
    }

    /** 编辑圈子信息：创建者全量保存名称、介绍和头像，省略头像表示移除自定义头像。 */
    @PutMapping("/{circleId}")
    @Operation(summary = "编辑圈子信息")
    @ApiResponse(responseCode = "403", description = "有效成员不是创建者")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    public Result<Void> updateCircle(
            @PathVariable UUID circleId,
            @Valid @RequestBody UpdateCircleRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 委托事务业务更新圈子信息，成功后返回空数据。
        service.updateCircle(circleId, request, userId);
        return Result.ok(null);
    }

    /** 获取圈子邀请码 */
    @GetMapping("/{circleId}/invitation")
    @Operation(summary = "获取圈子邀请码")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    public Result<CircleInvitationResponse> getInvitation(
            @PathVariable UUID circleId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(service.getInvitation(circleId, userId));
    }

    /** 开启或关闭圈子邀请 */
    @PutMapping("/{circleId}/invitation")
    @Operation(summary = "开启或关闭圈子邀请")
    @ApiResponse(responseCode = "403", description = "只有创建者可以操作")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    public Result<CircleInvitationResponse> updateInvitation(
            @PathVariable UUID circleId,
            @Valid @RequestBody UpdateCircleInvitationRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(service.updateInvitation(circleId, request, userId));
    }

    /** 重置圈子邀请码 */
    @PostMapping("/{circleId}/invitation/reset")
    @Operation(summary = "重置圈子邀请码")
    @ApiResponse(responseCode = "403", description = "只有创建者可以操作")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    public Result<CircleInvitationResponse> resetInvitation(
            @PathVariable UUID circleId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(service.resetInvitation(circleId, userId));
    }

    /** 查看圈子加入预览 */
    @PostMapping("/join-preview")
    @Operation(summary = "查看圈子加入预览")
    @ApiResponse(responseCode = "403", description = "当前用户被限制加入")
    @ApiResponse(responseCode = "404", description = "邀请码无效或圈子已解散")
    @ApiResponse(responseCode = "409", description = "圈子邀请已关闭")
    public Result<CircleJoinPreviewResponse> previewJoin(
            @Valid @RequestBody CircleJoinPreviewRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(service.previewJoin(request, userId));
    }

    /** 确认加入圈子 */
    @PostMapping("/join")
    @Operation(summary = "确认加入圈子", description = "成功后可通过 GET /circles 刷新圈子列表")
    @ApiResponse(responseCode = "403", description = "当前用户被限制加入")
    @ApiResponse(responseCode = "404", description = "邀请码无效或圈子已解散")
    @ApiResponse(responseCode = "409", description = "圈子邀请已关闭或成员状态已变化")
    public Result<Void> join(
            @Valid @RequestBody JoinCircleRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 委托业务层写入成员关系，成功后返回空数据。
        service.join(request, userId);
        return Result.ok(null);
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

    /** 撤回圈子分享 */
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
