package com.you.youbackend.media.controller;

import com.you.youbackend.common.response.Result;
import com.you.youbackend.media.request.CreateUploadRequest;
import com.you.youbackend.media.request.TestUploadRequest;
import com.you.youbackend.media.response.CreateUploadResponse;
import com.you.youbackend.media.response.TestUploadResponse;
import com.you.youbackend.media.response.UploadStatusResponse;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.user.filter.JwtAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;


/**
 * 媒体文件接口。
 */
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "媒体", description = "图片直传相关接口")
@RestController
@RequestMapping("/media/uploads")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    /**
     * 申请有效期为 10 分钟的图片直传地址。
     *
     * @param request 图片类型及声明的文件大小
     * @return 预签名 PUT 上传地址
     */
    @Operation(
            summary = "申请图片上传地址",
            description = """
                    返回有效期为 10 分钟的预签名 PUT 地址。
                    客户端向该地址发送图片二进制内容，
                    Content-Type 与申请时的 mimeType 保持一致。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "上传地址生成成功"),
            @ApiResponse(responseCode = "400", description = "请求参数或图片类型无效")
    })
    @PostMapping
    public Result<CreateUploadResponse> createUpload(
            @Valid @RequestBody CreateUploadRequest request,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId
    ) {
        return Result.ok(mediaService.createUpload(request,userId));
    }

    /**
     * 接收表单文件，通过给定的签名地址上传至 RustFS。
     *
     * @param request 完整签名地址及待上传文件
     * @return RustFS 返回的上传结果
     */
    @Operation(summary = "测试预签名上传", description = """
            以 multipart/form-data 提交 url 和 file。
            url 使用申请上传接口返回的完整地址，file 选择实际文件。
            文件部分的 Content-Type 应与申请时的 mimeType 一致。
            服务端将文件二进制通过 PUT 发送给 RustFS，返回存储服务的上传结果。
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "文件已上传至 RustFS"),
            @ApiResponse(responseCode = "400", description = "表单参数、文件或目标地址无效"),
            @ApiResponse(responseCode = "502", description = "RustFS 拒绝上传或上传连接失败")
    })
    @PostMapping(value = "/test", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<TestUploadResponse> testUpload(@Valid @ModelAttribute TestUploadRequest request) {
        return Result.ok(mediaService.testUpload(request));
    }

    /**
     * 查询图片是否已经登记入库，可供当前用户关联使用。
     */
    @Operation(
            summary = "查询图片上传就绪状态",
            description = "根据文件 ID 查询当前用户的有效文件记录，已登记时 ready 为 true")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "401", description = "登录凭证无效或已过期")
    })
    @GetMapping("/{fileId}")
    public Result<UploadStatusResponse> getUploadStatus(
            @PathVariable("fileId") UUID fileId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {

        return Result.ok(mediaService.getUploadStatus(fileId, userId));
    }
}