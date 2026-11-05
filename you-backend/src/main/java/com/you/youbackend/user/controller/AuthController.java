package com.you.youbackend.user.controller;

import com.you.youbackend.common.response.Result;
import com.you.youbackend.user.filter.JwtAuthFilter;
import com.you.youbackend.user.model.AccessTokenClaims;
import com.you.youbackend.user.request.LoginRequest;
import com.you.youbackend.user.request.RegisterRequest;
import com.you.youbackend.user.response.LoginResponse;
import com.you.youbackend.user.response.UserResponse;
import com.you.youbackend.user.service.TokenBlacklistService;
import com.you.youbackend.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 账号认证接口。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "账号认证", description = "注册与登录")
public class AuthController {

    private final UserService userService;
    private final TokenBlacklistService tokenBlacklistService;

    /**
     * 注册账号。
     */
    @Operation(summary = "注册账号", description = "创建用户并返回初始个人资料")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "注册成功"),
            @ApiResponse(responseCode = "400", description = "注册参数无效"),
            @ApiResponse(responseCode = "409", description = "用户名已被使用")
    })
    @PostMapping("/register")
    public Result<UserResponse> register(
            @Valid @RequestBody RegisterRequest request) {
        return Result.ok(userService.register(request));
    }

    /**
     * 登录并取得访问令牌和本人资料。
     */
    @Operation(
            summary = "用户登录",
            description = "验证账号密码，返回有效期为7天的访问令牌和本人资料")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功"),
            @ApiResponse(responseCode = "400", description = "登录参数无效"),
            @ApiResponse(responseCode = "401", description = "用户名或密码错误")
    })
    @PostMapping("/login")
    public Result<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {
        return Result.ok(userService.login(request));
    }

    /**
     * 撤销当前访问令牌，结束本次登录。
     */
    @Operation(
            summary = "退出登录",
            description = "撤销当前访问令牌，成功后客户端清除本地登录凭据"
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "退出成功"),
            @ApiResponse(responseCode = "401", description = "登录凭证无效或已失效")
    })
    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestAttribute(JwtAuthFilter.CURRENT_ACCESS_TOKEN)
            AccessTokenClaims token) {

        tokenBlacklistService.revoke(token);
        return Result.ok(null);
    }
}