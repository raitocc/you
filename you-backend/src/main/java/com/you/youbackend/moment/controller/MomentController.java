package com.you.youbackend.moment.controller;

import com.you.youbackend.common.response.Result;
import com.you.youbackend.moment.request.CreateMomentRequest;
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
import jakarta.validation.constraints.Pattern;
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
            @PathVariable("momentId")
            @Pattern(regexp = CreateMomentRequest.ID_PATTERN, message = "瞬间 ID 必须是 UUIDv7")
            String momentId,
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(momentService.getMoment(UUID.fromString(momentId), userId));
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
