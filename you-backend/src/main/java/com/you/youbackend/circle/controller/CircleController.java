package com.you.youbackend.circle.controller;

import com.you.youbackend.album.response.AlbumDetailedResponse;
import com.you.youbackend.circle.model.CircleListCursor;
import com.you.youbackend.circle.model.CircleShareCursor;
import com.you.youbackend.circle.model.CircleShareMomentCursor;
import com.you.youbackend.circle.request.CircleJoinPreviewRequest;
import com.you.youbackend.circle.request.CreateCircleRequest;
import com.you.youbackend.circle.request.CreateCircleSharesRequest;
import com.you.youbackend.circle.request.JoinCircleRequest;
import com.you.youbackend.circle.request.UpdateCircleInvitationRequest;
import com.you.youbackend.circle.request.UpdateCircleRequest;
import com.you.youbackend.circle.response.CircleDetailedResponse;
import com.you.youbackend.circle.response.CircleInvitationResponse;
import com.you.youbackend.circle.response.CircleJoinPreviewResponse;
import com.you.youbackend.circle.response.CircleJoinRestrictionResponse;
import com.you.youbackend.circle.response.CircleSummaryResponse;
import com.you.youbackend.circle.response.CircleShareFeedResponse;
import com.you.youbackend.circle.service.CircleService;
import com.you.youbackend.common.request.PageQuery;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.Result;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.common.validation.UUIDv7;
import com.you.youbackend.moment.response.MomentDetailedResponse;
import com.you.youbackend.user.filter.JwtAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 圈子接口入口，使用统一认证上下文委托业务处理。 */
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "圈子")
@RestController
@RequestMapping("/circles")
@ApiResponse(responseCode = "400", description = "参数无效或头像不可用")
@ApiResponse(responseCode = "401", description = "未登录或凭证失效")
@RequiredArgsConstructor
public class CircleController {
    private final CircleService circleService;

    /** 创建圈子和创建者成员关系；重复 ID 返回 409，不覆盖已有圈子。 */
    @Operation(summary = "创建圈子")
    @ApiResponse(responseCode = "200", description = "圈子创建成功")
    @ApiResponse(responseCode = "404", description = "账号不存在")
    @ApiResponse(responseCode = "409", description = "ID 已被占用")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Void> createCircle(
            @Valid
            @RequestBody
            CreateCircleRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        circleService.createCircle(request, userId);
        return Result.ok(null);
    }

    /** 按加入时间倒序分页浏览本人的圈子，头像未设置或失效时返回默认头像。 */
    @Operation(summary = "查看本人圈子列表")
    @Parameters({
            @Parameter(
                    name = "limit",
                    in = ParameterIn.QUERY,
                    description = "每页数量，默认 20，范围 1～100",
                    schema = @Schema(
                            type = "integer",
                            defaultValue = "20",
                            minimum = "1",
                            maximum = "100")),
            @Parameter(
                    name = "cursor.joinedAt",
                    in = ParameterIn.QUERY,
                    description = "上一页末项的成员关系创建时间，首屏省略",
                    schema = @Schema(
                            type = "string",
                            format = "date-time")),
            @Parameter(
                    name = "cursor.id",
                    in = ParameterIn.QUERY,
                    description = "与加入时间成组提交的上一页末项圈子 ID",
                    schema = @Schema(
                            type = "string",
                            format = "uuid"))
    })
    @GetMapping
    public Result<ResultPage<CircleSummaryResponse, CircleListCursor>> browseMyCircles(
            @Parameter(hidden = true)
            @PageQuery
            PageRequest<CircleListCursor> request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(circleService.browseMyCircles(userId, request));
    }

    /** 查看圈子详情；不存在、已解散和非成员统一返回 404。 */
    @Operation(summary = "查看圈子详情", description = "返回创建者公开资料及全部有效成员")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    @GetMapping("/{circleId}")
    public Result<CircleDetailedResponse> getCircleInfo(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(circleService.getCircleInfo(circleId, userId));
    }

    /** 创建者全量保存名称、介绍和头像；介绍和头像传 null 表示清空。 */
    @Operation(summary = "编辑圈子信息")
    @ApiResponse(responseCode = "403", description = "有效成员不是创建者")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    @PutMapping(value = "/{circleId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Void> updateCircle(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @Valid
            @RequestBody
            UpdateCircleRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        circleService.updateCircle(circleId, request, userId);
        return Result.ok(null);
    }

    /** 读取圈子邀请状态；邀请关闭时邀请码为 null。 */
    @Operation(summary = "获取圈子邀请码")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    @GetMapping("/{circleId}/invitation")
    public Result<CircleInvitationResponse> getInvitation(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(circleService.getInvitation(circleId, userId));
    }

    /** 创建者开启或关闭圈子邀请，邀请码保持不变。 */
    @Operation(summary = "开启或关闭圈子邀请")
    @ApiResponse(responseCode = "403", description = "有效成员不是创建者")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    @PutMapping(value = "/{circleId}/invitation", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<CircleInvitationResponse> updateInvitation(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @Valid
            @RequestBody
            UpdateCircleInvitationRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(circleService.updateInvitation(circleId, request, userId));
    }

    /** 创建者重置圈子邀请码，旧邀请码立即失效。 */
    @Operation(summary = "重置圈子邀请码")
    @ApiResponse(responseCode = "403", description = "有效成员不是创建者")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    @ApiResponse(responseCode = "409", description = "邀请码生成冲突，请重新重置")
    @PostMapping("/{circleId}/invitation/reset")
    public Result<CircleInvitationResponse> resetInvitation(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(circleService.resetInvitation(circleId, userId));
    }

    /** 按邀请码预览加入目标，返回圈子资料和当前成员标记，只读取数据。 */
    @Operation(summary = "查看圈子加入预览")
    @ApiResponse(responseCode = "403", description = "当前用户被限制加入")
    @ApiResponse(responseCode = "404", description = "邀请码无效或圈子已解散")
    @ApiResponse(responseCode = "409", description = "圈子邀请已关闭")
    @PostMapping(value = "/join-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<CircleJoinPreviewResponse> previewJoin(
            @Valid
            @RequestBody
            CircleJoinPreviewRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(circleService.previewJoin(request, userId));
    }

    /** 确认加入圈子：首次新增、已离开恢复、已是成员直接成功。 */
    @Operation(summary = "确认加入圈子", description = "成功后可通过 GET /circles 刷新圈子列表")
    @ApiResponse(responseCode = "403", description = "当前用户被限制加入")
    @ApiResponse(responseCode = "404", description = "邀请码无效或圈子已解散")
    @ApiResponse(responseCode = "409", description = "圈子邀请已关闭或成员状态已变化")
    @PostMapping(value = "/join", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Void> joinCircle(
            @Valid
            @RequestBody
            JoinCircleRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        circleService.joinCircle(request, userId);
        return Result.ok(null);
    }

    /** 创建者移除成员：撤回其圈内分享并限制再次加入；重复移除受限成员返回成功。 */
    @Operation(summary = "移除圈子成员")
    @ApiResponse(responseCode = "403", description = "无权移除成员或创建者尝试移除自己")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    @ApiResponse(responseCode = "409", description = "目标用户不可移除")
    @DeleteMapping("/{circleId}/members/{userId}")
    public Result<Void> removeMember(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @Parameter(description = "被移除的成员用户 UUIDv7")
            @PathVariable("userId")
            @UUIDv7(checkTimestamp = false)
            UUID targetId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        circleService.removeMember(circleId, targetId, userId);
        return Result.ok(null);
    }

    /** 创建者解除指定用户的加入限制，成员身份和分享状态不变。 */
    @Operation(summary = "解除圈子加入限制")
    @ApiResponse(responseCode = "403", description = "有效成员不是创建者")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    @DeleteMapping("/{circleId}/join-restrictions/{userId}")
    public Result<Void> clearJoinRestriction(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @Parameter(description = "被解除限制的用户 UUIDv7")
            @PathVariable("userId")
            @UUIDv7(checkTimestamp = false)
            UUID targetId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        circleService.clearJoinRestriction(circleId, targetId, userId);
        return Result.ok(null);
    }

    /** 创建者查看仍被限制再次加入的用户，按移除时间倒序。 */
    @Operation(summary = "查看被限制加入的用户")
    @ApiResponse(responseCode = "403", description = "有效成员不是创建者")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    @GetMapping("/{circleId}/join-restrictions")
    public Result<List<CircleJoinRestrictionResponse>> listJoinRestrictions(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(circleService.listJoinRestrictions(circleId, userId));
    }

    /** 普通成员主动退出并撤回本人圈内分享；创建者须改用解散，不设置加入限制。 */
    @Operation(summary = "退出圈子")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    @ApiResponse(responseCode = "409", description = "创建者不能直接退出")
    @DeleteMapping("/{circleId}/members/me")
    public Result<Void> leaveCircle(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        circleService.leaveCircle(circleId, userId);
        return Result.ok(null);
    }

    /** 创建者解散圈子：邀请关闭，成员关系和圈内分享全部失效，原内容保留。 */
    @Operation(summary = "解散圈子")
    @ApiResponse(responseCode = "403", description = "有效成员不是创建者")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    @DeleteMapping("/{circleId}")
    public Result<Void> dissolveCircle(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        circleService.dissolveCircle(circleId, userId);
        return Result.ok(null);
    }

    /** 把本人的一条瞬间或时光分享到多个圈子；已有效的分享跳过，已撤回的恢复。 */
    @Operation(summary = "分享到多个圈子")
    @ApiResponse(responseCode = "403", description = "只能分享本人内容")
    @ApiResponse(responseCode = "404", description = "原内容或目标圈子不存在或不可访问")
    @ApiResponse(responseCode = "409", description = "RESOURCE_CONFLICT 并发分享冲突")
    @PostMapping(value = "/shares", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Void> shareToCircles(
            @Valid
            @RequestBody
            CreateCircleSharesRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        circleService.shareToCircles(request, userId);
        return Result.ok(null);
    }

    /** 撤回圈内的一条分享：分享者或圈子创建者可操作，重复撤回返回成功，原内容保留。 */
    @Operation(summary = "撤回圈子分享")
    @ApiResponse(responseCode = "403", description = "只有分享者或圈子创建者可以撤回")
    @ApiResponse(responseCode = "404", description = "圈子或分享不存在或不可访问")
    @DeleteMapping("/{circleId}/shares/{shareId}")
    public Result<Void> withdrawShare(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @Parameter(description = "分享关系 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID shareId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        circleService.withdrawShare(circleId, shareId, userId);
        return Result.ok(null);
    }

    /** 按分享时间倒序分页浏览圈内分享流，条目含原内容卡片和分享者资料。 */
    @Operation(summary = "分页浏览圈子分享")
    @ApiResponse(responseCode = "404", description = "圈子不存在或不可访问")
    @Parameters({
            @Parameter(
                    name = "limit",
                    in = ParameterIn.QUERY,
                    description = "每页数量，默认 20，范围 1～100",
                    schema = @Schema(
                            type = "integer",
                            defaultValue = "20",
                            minimum = "1",
                            maximum = "100")),
            @Parameter(
                    name = "cursor.sharedAt",
                    in = ParameterIn.QUERY,
                    description = "上一页末条分享时间，首屏省略",
                    schema = @Schema(
                            type = "string",
                            format = "date-time")),
            @Parameter(
                    name = "cursor.id",
                    in = ParameterIn.QUERY,
                    description = "与分享时间成组提交的上一页末条分享 ID",
                    schema = @Schema(
                            type = "string",
                            format = "uuid"))
    })
    @GetMapping("/{circleId}/shares")
    public Result<ResultPage<CircleShareFeedResponse, CircleShareCursor>> browseShares(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @Parameter(hidden = true)
            @PageQuery
            PageRequest<CircleShareCursor> request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(circleService.browseShares(circleId, userId, request));
    }

    /** 沿圈子分享读取完整瞬间；分享类型不匹配返回 409。 */
    @Operation(summary = "查看圈子分享的瞬间详情")
    @ApiResponse(responseCode = "404", description = "圈子、分享或瞬间不存在或不可访问")
    @ApiResponse(responseCode = "409", description = "分享不是瞬间")
    @GetMapping("/{circleId}/shares/{shareId}/moment")
    public Result<MomentDetailedResponse> getSharedMoment(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @Parameter(description = "圈子分享关系 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID shareId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(circleService.getSharedMoment(circleId, shareId, userId));
    }

    /** 沿圈子分享读取时光详情；分享类型不匹配返回 409。 */
    @Operation(summary = "查看圈子分享的时光详情")
    @ApiResponse(responseCode = "404", description = "圈子、分享或时光不存在或不可访问")
    @ApiResponse(responseCode = "409", description = "分享不是时光")
    @GetMapping("/{circleId}/shares/{shareId}/album")
    public Result<AlbumDetailedResponse> getSharedAlbum(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @Parameter(description = "圈子分享关系 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID shareId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(circleService.getSharedAlbum(circleId, shareId, userId));
    }

    /** 沿圈子共享时光读取其中的瞬间详情；分享类型不匹配返回 409。 */
    @Operation(summary = "查看圈子分享时光内的瞬间详情")
    @ApiResponse(responseCode = "404", description = "圈子、分享、时光或内部瞬间不存在或不可访问")
    @ApiResponse(responseCode = "409", description = "分享不是时光")
    @GetMapping("/{circleId}/shares/{shareId}/moments/{momentId}")
    public Result<MomentDetailedResponse> getSharedAlbumMoment(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @Parameter(description = "圈子分享关系 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID shareId,

            @Parameter(description = "时光内瞬间 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID momentId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(circleService.getSharedAlbumMoment(circleId, shareId, momentId, userId));
    }

    /** 按发生时间正序分页浏览圈子共享时光中的瞬间。 */
    @Operation(summary = "分页浏览圈子共享时光中的瞬间")
    @ApiResponse(responseCode = "404", description = "圈子或分享不存在或不可访问")
    @ApiResponse(responseCode = "409", description = "分享不是时光")
    @Parameters({
            @Parameter(
                    name = "limit",
                    in = ParameterIn.QUERY,
                    description = "每页数量，默认 20，范围 1～100",
                    schema = @Schema(
                            type = "integer",
                            defaultValue = "20",
                            minimum = "1",
                            maximum = "100")),
            @Parameter(
                    name = "cursor.memoryTime",
                    in = ParameterIn.QUERY,
                    description = "上一页末项发生时间，首屏省略",
                    schema = @Schema(
                            type = "string",
                            format = "date-time")),
            @Parameter(
                    name = "cursor.id",
                    in = ParameterIn.QUERY,
                    description = "与发生时间成组提交的上一页末项瞬间 ID",
                    schema = @Schema(
                            type = "string",
                            format = "uuid"))
    })
    @GetMapping("/{circleId}/shares/{shareId}/moments")
    public Result<ResultPage<MomentDetailedResponse, CircleShareMomentCursor>> browseSharedAlbumMoments(
            @Parameter(description = "圈子 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID circleId,

            @Parameter(description = "圈子分享关系 UUIDv7")
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID shareId,

            @Parameter(hidden = true)
            @PageQuery
            PageRequest<CircleShareMomentCursor> request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(circleService.browseSharedAlbumMoments(circleId, shareId, userId, request));
    }
}
