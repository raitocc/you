package com.you.youbackend.media.controller;

import com.you.youbackend.common.response.Result;
import com.you.youbackend.media.request.CreateUploadRequest;
import com.you.youbackend.media.response.CreateUploadResponse;
import com.you.youbackend.media.response.UploadStatusResponse;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.user.filter.JwtAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 图片直传地址申请与上传就绪查询入口。
 */
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "媒体")
@RestController
@RequestMapping("/media/uploads")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    @Operation(
            summary = "申请图片上传地址",
            description = """
                    返回有效期为 10 分钟的预签名 PUT 地址。
                    客户端向该地址发送图片二进制内容，
                    Content-Type 与申请时的 mimeType 保持一致。
                    """)
    @ApiResponse(responseCode = "200", description = "上传地址生成成功")
    @ApiResponse(responseCode = "400", description = "请求参数或图片类型无效")
    @ApiResponse(responseCode = "401", description = "登录凭证无效或已过期")
    @PostMapping
    public Result<CreateUploadResponse> createUpload(
            @Valid
            @RequestBody
            CreateUploadRequest request,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(mediaService.createUpload(request, userId));
    }

    @Operation(
            summary = "查询图片上传就绪状态",
            description = "根据文件 ID 查询当前用户的有效文件记录，已登记时 ready 为 true")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @ApiResponse(responseCode = "401", description = "登录凭证无效或已过期")
    @GetMapping("/{fileId}")
    public Result<UploadStatusResponse> getUploadStatus(
            @PathVariable UUID fileId,

            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID)
            UUID userId) {
        return Result.ok(mediaService.getUploadStatus(fileId, userId));
    }
}
