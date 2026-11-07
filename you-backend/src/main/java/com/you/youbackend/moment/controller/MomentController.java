package com.you.youbackend.moment.controller;

import com.you.youbackend.common.validation.UUIDv7;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.Result;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.moment.model.MomentFeedCursor;
import com.you.youbackend.moment.request.CreateMomentRequest;
import com.you.youbackend.moment.request.UpdateMomentRequest;
import com.you.youbackend.moment.response.MomentFeedItem;
import com.you.youbackend.moment.response.MomentResponse;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.filter.JwtAuthFilter;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@SecurityRequirement(name = "bearerAuth")
@Tag(name = "瞬间")
@RestController
@RequestMapping("/moments")
@RequiredArgsConstructor
public class MomentController {

    private final MomentService momentService;

    /**
     * 浏览本人瞬间，首屏传空对象，后续提交上次返回的结构化游标。
     * TODO：同步前端和 Apifox 接口契约：GET /moments/yours 改为 POST /moments/yours；
     * 请求改为 JSON {cursor: {memoryTime, id}, limit}，响应 nextCursor 改为同结构对象，末页为 null。
     */
    @Operation(summary = "浏览自己的瞬间流", operationId = "getMomentYours")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "分页参数无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或过期")
    @PostMapping(path = "/yours", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<ResultPage<MomentFeedItem, MomentFeedCursor>> browseYours(
            @Valid @RequestBody PageRequest<MomentFeedCursor> request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(momentService.browseYours(userId, request));
    }

    /**
     * 查看本人发布的瞬间，供阅读和编辑回填。
     */
    @Operation(summary = "查看瞬间详情", operationId = "getMoment")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "瞬间 ID 格式无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或过期")
    @ApiResponse(responseCode = "403", description = "当前用户不是作者")
    @ApiResponse(responseCode = "404", description = "瞬间不存在或已删除")
    @GetMapping("/{momentId}")
    public Result<MomentResponse> getMoment(
            @Parameter(description = "瞬间 UUIDv7", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID momentId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(momentService.getMoment(momentId, userId));
    }

    /**
     * 全量保存本人瞬间的可编辑内容并返回最新详情。
     */
    @Operation(summary = "全量修改瞬间", operationId = "updateMoment")
    @ApiResponse(responseCode = "200", description = "修改成功")
    @ApiResponse(responseCode = "400", description = "参数或图片校验失败")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或过期")
    @ApiResponse(responseCode = "403", description = "当前用户不是作者")
    @ApiResponse(responseCode = "404", description = "瞬间不存在或已删除")
    @PutMapping(path = "/{momentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<MomentResponse> updateMoment(
            @Parameter(description = "瞬间 UUIDv7", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID momentId,
            @Valid @RequestBody UpdateMomentRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(momentService.updateMoment(momentId, request, userId));
    }

    /**
     * 删除本人瞬间并解除图片和标签关联。
     */
    @Operation(summary = "删除瞬间", operationId = "deleteMoment")
    @ApiResponse(responseCode = "200", description = "删除成功")
    @ApiResponse(responseCode = "400", description = "瞬间 ID 格式无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或过期")
    @ApiResponse(responseCode = "403", description = "当前用户不是作者")
    @ApiResponse(responseCode = "404", description = "瞬间不存在")
    @DeleteMapping("/{momentId}")
    public Result<Void> deleteMoment(
            @Parameter(description = "瞬间 UUIDv7", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID momentId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        momentService.deleteMoment(momentId, userId);
        return Result.ok(null);
    }

    /**
     * 发布瞬间并返回详情。
     */
    @Operation(summary = "发布瞬间", operationId = "createMoment")
    @ApiResponse(responseCode = "201", description = "发布成功")
    @ApiResponse(responseCode = "400", description = "字段或图片校验失败")
    @ApiResponse(responseCode = "401", description = "登录凭证无效或已过期")
    @ApiResponse(responseCode = "409", description = "瞬间 ID 已被使用")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<MomentResponse> createMoment(
            @Valid @RequestBody CreateMomentRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {

        return Result.ok(momentService.createMoment(request, userId));
    }
}
