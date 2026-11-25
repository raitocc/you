package com.you.youbackend.moment.controller;

import com.you.youbackend.common.request.PageQuery;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.Result;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.common.validation.UUIDv7;
import com.you.youbackend.moment.model.MomentFeedCursor;
import com.you.youbackend.moment.model.RelatedFeedCursor;
import com.you.youbackend.moment.request.CreateMomentRequest;
import com.you.youbackend.moment.request.UpdateMomentRequest;
import com.you.youbackend.moment.response.MomentDetailedResponse;
import com.you.youbackend.moment.response.MomentSummaryResponse;
import com.you.youbackend.moment.response.RelatedFeedResponse;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.moment.service.RelatedFeedService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;


@Tag(name = "瞬间")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/moments")
@RequiredArgsConstructor
public class MomentController {

    private final MomentService momentService;

    private final RelatedFeedService relatedFeedService;

    /**
     * 浏览本人瞬间
     */
    @Operation(summary = "浏览自己的瞬间流")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "分页参数无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或过期")
    @Parameters({
            @Parameter(
                    name = "limit",
                    in = ParameterIn.QUERY,
                    description = "每页数量，默认 20",
                    schema = @Schema(
                            type = "integer",
                            defaultValue = "20",
                            minimum = "1",
                            maximum = "100")),
            @Parameter(
                    name = "cursor.memoryTime",
                    in = ParameterIn.QUERY,
                    description = "上一页最后一条记录的发生时间；首屏省略",
                    schema = @Schema(
                            type = "string",
                            format = "date-time")),
            @Parameter(
                    name = "cursor.id",
                    in = ParameterIn.QUERY,
                    description = "与游标时间成组提供的 UUIDv7；首屏省略",
                    schema = @Schema(
                            type = "string",
                            format = "uuid"))
    })
    @GetMapping("/yours")
    public Result<ResultPage<MomentSummaryResponse, MomentFeedCursor>> browseYours(
            // 分页请求
            @Parameter(hidden = true)
            @PageQuery
            PageRequest<MomentFeedCursor> request,
            // 用户id
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(momentService.browseYours(userId, request));
    }

    /**
     * 查看本人发布的瞬间，供阅读和编辑回填。
     */
    @Operation(summary = "查看瞬间详情")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "瞬间 ID 格式无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或过期")
    @ApiResponse(responseCode = "403", description = "当前用户不是作者")
    @ApiResponse(responseCode = "404", description = "瞬间不存在或已删除")
    @GetMapping("/{momentId}")
    public Result<MomentDetailedResponse> getMoment(
            @Parameter(
                    description = "瞬间 UUIDv7",
                    schema = @Schema(
                            type = "string",
                            format = "uuid"))
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID momentId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(momentService.getMoment(momentId, userId));
    }

    /**
     * 全量保存本人瞬间的可编辑内容并返回最新详情。
     */
    @Operation(summary = "全量修改瞬间")
    @ApiResponse(responseCode = "200", description = "修改成功")
    @ApiResponse(responseCode = "400", description = "参数或图片校验失败")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或过期")
    @ApiResponse(responseCode = "403", description = "当前用户不是作者")
    @ApiResponse(responseCode = "404", description = "瞬间不存在或已删除")
    @PutMapping(path = "/{momentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<MomentDetailedResponse> updateMoment(
            @Parameter(
                    description = "瞬间 UUIDv7",
                    schema = @Schema(
                            type = "string",
                            format = "uuid"))
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID momentId,

            @Valid
            @RequestBody
            UpdateMomentRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(momentService.updateMoment(momentId, request, userId));
    }

    /**
     * 删除本人瞬间并解除图片和标签关联。
     */
    @Operation(summary = "删除瞬间")
    @ApiResponse(responseCode = "200", description = "删除成功")
    @ApiResponse(responseCode = "400", description = "瞬间 ID 格式无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或过期")
    @ApiResponse(responseCode = "403", description = "当前用户不是作者")
    @ApiResponse(responseCode = "404", description = "瞬间不存在")
    @DeleteMapping("/{momentId}")
    public Result<Void> deleteMoment(
            @Parameter(
                    description = "瞬间 UUIDv7",
                    schema = @Schema(
                            type = "string",
                            format = "uuid"))
            @PathVariable
            @UUIDv7(checkTimestamp = false)
            UUID momentId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        momentService.deleteMoment(momentId, userId);
        return Result.ok(null);
    }

    /**
     * 发布瞬间并返回详情。
     */
    @Operation(summary = "发布瞬间")
    @ApiResponse(responseCode = "201", description = "发布成功")
    @ApiResponse(responseCode = "400", description = "字段或图片校验失败")
    @ApiResponse(responseCode = "401", description = "登录凭证无效或已过期")
    @ApiResponse(responseCode = "409", description = "瞬间 ID 已被使用")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<MomentDetailedResponse> createMoment(
            @Valid
            @RequestBody
            CreateMomentRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(momentService.createMoment(request, userId));
    }

    /**
     * 浏览与你有关的动态流
     */
    @Operation(summary = "浏览与你有关的动态流")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "分页参数无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或过期")
    @Parameters({
            @Parameter(
                    name = "limit",
                    in = ParameterIn.QUERY,
                    description = "每页数量",
                    schema = @Schema(
                            type = "integer",
                            defaultValue = "20",
                            minimum = "1",
                            maximum = "100"
                    )
            ),
            @Parameter(
                    name = "cursor.sharedAt",
                    in = ParameterIn.QUERY,
                    description = "上一页最后一条动态的分享时间",
                    schema = @Schema(type = "string", format = "date-time")
            ),
            @Parameter(
                    name = "cursor.id",
                    in = ParameterIn.QUERY,
                    description = "同一条信箱动态的 ID",
                    schema = @Schema(type = "string", format = "uuid")
            )
    })
    @GetMapping("/related")
    public Result<ResultPage<RelatedFeedResponse, RelatedFeedCursor>> browseRelated(
            // 分页请求
            @Parameter(hidden = true)
            @PageQuery
            PageRequest<RelatedFeedCursor> request,

            // 用户 ID
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId
    ) {
        return Result.ok(relatedFeedService.browseRelated(userId, request));
    }

}
