package com.you.youbackend.user.controller;

import com.you.youbackend.common.response.Result;
import com.you.youbackend.user.filter.JwtAuthFilter;
import com.you.youbackend.user.request.UpdateProfileRequest;
import com.you.youbackend.user.response.UserResponse;
import com.you.youbackend.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户个人资料接口。
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "用户资料", description = "当前用户的个人资料")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    /**
     * 获取当前登录用户的资料。
     */
    @Operation(
            summary = "获取本人资料",
            description = "携带 Bearer 令牌，返回当前登录用户的最新资料")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(
                    responseCode = "401",
                    description = "登录凭证无效、已过期或账号已失效")
    })
    @GetMapping("/me")
    public Result<UserResponse> getCurrentUser(
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId) {
        return Result.ok(userService.getCurrentUser(userId));
    }

    /**
     * 更新当前登录用户的昵称和头像，返回最新资料。
     */
    @Operation(
            summary = "更新本人资料",
            description = "全量提交昵称和头像文件 ID，avatarFileId 为 null 时移除头像")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "400", description = "昵称无效或头像文件不可用"),
            @ApiResponse(responseCode = "401", description = "登录凭证无效或账号已失效")
    })
    @PutMapping("/me")
    public Result<UserResponse> updateProfile(
            @RequestAttribute(JwtAuthFilter.CURRENT_USER_ID) UUID userId,
            @Valid @RequestBody UpdateProfileRequest request) {

        return Result.ok(userService.updateProfile(userId, request));
    }
}