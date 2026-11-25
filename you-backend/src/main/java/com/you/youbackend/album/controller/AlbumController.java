package com.you.youbackend.album.controller;

import com.you.youbackend.album.request.CreateAlbumRequest;
import com.you.youbackend.album.request.AddAlbumMomentsRequest;
import com.you.youbackend.album.request.RemoveAlbumMomentsRequest;
import com.you.youbackend.album.request.UpdateAlbumRequest;
import com.you.youbackend.album.response.AlbumDetailedResponse;
import com.you.youbackend.album.response.AlbumSummaryResponse;
import com.you.youbackend.album.model.AlbumListCursor;
import com.you.youbackend.album.model.AlbumMomentCursor;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.request.PageQuery;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.moment.response.MomentDetailedResponse;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.common.response.Result;
import com.you.youbackend.moment.response.MomentSummaryResponse;
import com.you.youbackend.user.filter.JwtAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 当前用户的时光创建、信息维护、删除与查询入口。 */
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "时光")
@RestController
@RequestMapping("/albums")
@RequiredArgsConstructor
public class AlbumController {
    private final AlbumService albumService;

    @Operation(summary = "批量添加瞬间")
    @ApiResponse(responseCode = "200", description = "操作成功，返回最新时光信息")
    @ApiResponse(responseCode = "400", description = "时光 ID 或瞬间参数无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是时光所有者")
    @ApiResponse(responseCode = "404", description = "时光不存在或已删除")
    @PostMapping(value = "/{albumId}/moments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<AlbumDetailedResponse> addAlbumMoments(
            @Parameter(description = "时光 UUID")
            @PathVariable UUID albumId,

            @Valid
            @RequestBody AddAlbumMomentsRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(albumService.addAlbumMoments(albumId, request, userId));
    }

    @Operation(summary = "批量移出瞬间")
    @ApiResponse(responseCode = "200", description = "操作成功，返回最新时光信息")
    @ApiResponse(responseCode = "400", description = "时光 ID 或瞬间参数无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是时光所有者")
    @ApiResponse(responseCode = "404", description = "时光不存在或已删除")
    @PostMapping(value = "/{albumId}/moments/remove", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<AlbumDetailedResponse> removeAlbumMoments(
            @Parameter(description = "时光 UUID")
            @PathVariable
            UUID albumId,

            @Valid
            @RequestBody
            RemoveAlbumMomentsRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(albumService.removeAlbumMoments(albumId, request, userId));
    }

    @Operation(summary = "修改时光信息")
    @ApiResponse(responseCode = "200", description = "修改成功")
    @ApiResponse(responseCode = "400", description = "时光 ID、名称、介绍或封面无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是时光所有者")
    @ApiResponse(responseCode = "404", description = "时光不存在或已删除")
    @PutMapping(value = "/{albumId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<AlbumDetailedResponse> updateAlbum(
            @Parameter(description = "时光 UUID")
            @PathVariable
            UUID albumId,

            @Valid
            @RequestBody
            UpdateAlbumRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(albumService.updateAlbum(albumId, request, userId));
    }

    @Operation(summary = "删除时光")
    @ApiResponse(responseCode = "200", description = "删除成功，data 为 null")
    @ApiResponse(responseCode = "400", description = "时光 ID 格式无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是时光所有者")
    @ApiResponse(responseCode = "404", description = "时光不存在")
    @DeleteMapping("/{albumId}")
    public Result<Void> deleteAlbum(
            @Parameter(description = "时光 UUID")
            @PathVariable
            UUID albumId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        albumService.deleteAlbum(albumId, userId);
        return Result.ok(null);
    }

    @Operation(summary = "浏览自己的时光列表")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "分页参数或游标无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
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
                    name = "cursor.createdAt",
                    in = ParameterIn.QUERY,
                    description = "上一页最后一条记录的创建时间；首屏省略",
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
    @GetMapping
    public Result<ResultPage<AlbumSummaryResponse, AlbumListCursor>> browseAlbums(
            @Parameter(hidden = true)
            @PageQuery
            PageRequest<AlbumListCursor> request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(albumService.browseAlbums(userId, request));
    }

    @Operation(summary = "分页浏览时光中的瞬间")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "时光 ID、分页参数或游标无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是时光所有者")
    @ApiResponse(responseCode = "404", description = "时光不存在或已删除")
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
                    name = "cursor.createdAt",
                    in = ParameterIn.QUERY,
                    description = "上一页最后一条记录的发布时间；首屏省略",
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
    @GetMapping("/{albumId}/moments")
    public Result<ResultPage<MomentSummaryResponse, AlbumMomentCursor>> browseAlbumMoments( // TODO 这里从 Detailed 改成了 Summary！
            @Parameter(description = "时光 UUID")
            @PathVariable
            UUID albumId,

            @Parameter(hidden = true)
            @PageQuery
            PageRequest<AlbumMomentCursor> request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(albumService.browseAlbumMoments(albumId, userId, request));
    }

    @Operation(summary = "手动创建时光")
    @ApiResponse(responseCode = "201", description = "创建成功")
    @ApiResponse(responseCode = "400", description = "名称、介绍、封面或瞬间无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<AlbumDetailedResponse> createAlbum(
            @Valid
            @RequestBody
            CreateAlbumRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(albumService.createAlbum(request, userId));
    }

    @Operation(summary = "查看时光信息")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "时光 ID 格式无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是时光所有者")
    @ApiResponse(responseCode = "404", description = "时光不存在或已删除")
    @GetMapping("/{albumId}")
    public Result<AlbumDetailedResponse> getAlbum(
            @Parameter(description = "时光 UUID") @PathVariable UUID albumId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(albumService.getAlbum(albumId, userId));
    }
}
