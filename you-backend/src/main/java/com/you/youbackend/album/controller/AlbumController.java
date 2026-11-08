package com.you.youbackend.album.controller;

import com.you.youbackend.album.request.CreateAlbumRequest;
import com.you.youbackend.album.request.UpdateAlbumRequest;
import com.you.youbackend.album.response.AlbumResponse;
import com.you.youbackend.album.response.AlbumListItem;
import com.you.youbackend.album.model.AlbumListCursor;
import com.you.youbackend.album.model.AlbumMomentCursor;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.request.PageQuery;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.moment.response.MomentResponse;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.common.response.Result;
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

    /** 全量修改本人时光展示信息，介绍和封面显式传 null 时清空。 */
    @Operation(summary = "修改时光信息", operationId = "04_update_album")
    @ApiResponse(responseCode = "200", description = "修改成功")
    @ApiResponse(responseCode = "400", description = "时光 ID、名称、介绍或封面无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是时光所有者")
    @ApiResponse(responseCode = "404", description = "时光不存在或已删除")
    @PutMapping(value = "/{albumId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<AlbumResponse> updateAlbum(
            @Parameter(description = "时光 UUID") @PathVariable UUID albumId,
            @Valid @RequestBody UpdateAlbumRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 使用认证上下文校验归属，委托业务层全量保存展示信息。
        return Result.ok(albumService.updateAlbum(albumId, request, userId));
    }

    /** 删除本人时光和有效关联，保留原瞬间，重复删除本人记录返回成功。 */
    @Operation(summary = "删除时光", operationId = "05_delete_album")
    @ApiResponse(responseCode = "200", description = "删除成功，data 为 null")
    @ApiResponse(responseCode = "400", description = "时光 ID 格式无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是时光所有者")
    @ApiResponse(responseCode = "404", description = "时光不存在")
    @DeleteMapping("/{albumId}")
    public Result<Void> deleteAlbum(
            @Parameter(description = "时光 UUID") @PathVariable UUID albumId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 归属检查、幂等判断及事务写入统一交给业务层。
        albumService.deleteAlbum(albumId, userId);
        return Result.ok(null);
    }

    /** 浏览私有时光列表，通过 cursor.字段名 提交结构化游标，始终限定当前用户。 */
    @Operation(summary = "浏览自己的时光列表", operationId = "01_list_albums")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "分页参数或游标无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @Parameters({
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页数量，默认 20",
                    schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
            @Parameter(name = "cursor.createdAt", in = ParameterIn.QUERY, description = "上一页最后一条记录的创建时间；首屏省略",
                    schema = @Schema(type = "string", format = "date-time")),
            @Parameter(name = "cursor.id", in = ParameterIn.QUERY, description = "与游标时间成组提供的 UUIDv7；首屏省略",
                    schema = @Schema(type = "string", format = "uuid"))
    })
    @GetMapping
    public Result<ResultPage<AlbumListItem, AlbumListCursor>> browseAlbums(
            @Parameter(hidden = true) @PageQuery PageRequest<AlbumListCursor> request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 以认证上下文限定所有者，委托业务层批量查询卡片及下一页位置。
        return Result.ok(albumService.browseAlbums(userId, request));
    }

    /** 按时间正序浏览本人时光中的瞬间，详情结构沿用当前瞬间实现。 */
    @Operation(summary = "分页浏览时光中的瞬间", operationId = "06_list_album_moments")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "时光 ID、分页参数或游标无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是时光所有者")
    @ApiResponse(responseCode = "404", description = "时光不存在或已删除")
    @Parameters({
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页数量，默认 20",
                    schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
            @Parameter(name = "cursor.memoryTime", in = ParameterIn.QUERY, description = "上一页最后一条记录的发生时间；首屏省略",
                    schema = @Schema(type = "string", format = "date-time")),
            @Parameter(name = "cursor.createdAt", in = ParameterIn.QUERY, description = "上一页最后一条记录的发布时间；首屏省略",
                    schema = @Schema(type = "string", format = "date-time")),
            @Parameter(name = "cursor.id", in = ParameterIn.QUERY, description = "与游标时间成组提供的 UUIDv7；首屏省略",
                    schema = @Schema(type = "string", format = "uuid"))
    })
    @GetMapping("/{albumId}/moments")
    public Result<ResultPage<MomentResponse, AlbumMomentCursor>> browseAlbumMoments(
            @Parameter(description = "时光 UUID") @PathVariable UUID albumId,
            @Parameter(hidden = true) @PageQuery PageRequest<AlbumMomentCursor> request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 每次查询都由业务层校验时光归属，再加载有效本人瞬间并包装分页结果。
        return Result.ok(albumService.browseAlbumMoments(albumId, userId, request));
    }

    /**
     * 手动创建属于当前用户的时光，允许初始瞬间为空。
     */
    @Operation(summary = "手动创建时光", operationId = "02_create_album")
    @ApiResponse(responseCode = "201", description = "创建成功")
    @ApiResponse(responseCode = "400", description = "名称、介绍、封面或瞬间无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<AlbumResponse> createAlbum(
            @Valid @RequestBody CreateAlbumRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 请求已完成字段校验，使用认证上下文中的用户创建时光并包装统一响应。
        return Result.ok(albumService.createAlbum(request, userId));
    }

    /** 查看本人时光信息，瞬间列表由独立的分页接口加载。 */
    @Operation(summary = "查看时光信息", operationId = "03_get_album")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "400", description = "时光 ID 格式无效")
    @ApiResponse(responseCode = "401", description = "登录凭证缺失、无效或已失效")
    @ApiResponse(responseCode = "403", description = "当前用户不是时光所有者")
    @ApiResponse(responseCode = "404", description = "时光不存在或已删除")
    @GetMapping("/{albumId}")
    public Result<AlbumResponse> getAlbum(
            @Parameter(description = "时光 UUID") @PathVariable UUID albumId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        // 将时光归属校验和详情加载交给业务层，统一包装查询结果。
        return Result.ok(albumService.getAlbum(albumId, userId));
    }
}
