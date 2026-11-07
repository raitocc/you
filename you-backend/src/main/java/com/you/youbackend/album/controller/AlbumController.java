package com.you.youbackend.album.controller;

import com.you.youbackend.album.request.CreateAlbumRequest;
import com.you.youbackend.album.response.AlbumResponse;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.common.response.Result;
import com.you.youbackend.user.filter.JwtAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
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

/** 当前用户的时光创建与信息查询入口。 */
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "时光")
@RestController
@RequestMapping("/albums")
@RequiredArgsConstructor
public class AlbumController {
    private final AlbumService albumService;

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
