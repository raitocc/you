package com.you.youbackend.user.controller;

import com.you.youbackend.common.response.Result;
import com.you.youbackend.user.request.LoginRequest;
import com.you.youbackend.user.request.RegisterRequest;
import com.you.youbackend.user.response.LoginResponse;
import com.you.youbackend.user.response.UserResponse;
import com.you.youbackend.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号认证接口。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "账号认证", description = "注册与登录")
public class AuthController {

    private final UserService userService;

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
}