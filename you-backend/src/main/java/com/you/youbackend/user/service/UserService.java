package com.you.youbackend.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.user.mapper.UserMapper;
import com.you.youbackend.user.model.AppUser;
import com.you.youbackend.user.request.LoginRequest;
import com.you.youbackend.user.request.RegisterRequest;
import com.you.youbackend.user.response.LoginResponse;
import com.you.youbackend.user.response.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/**
 * 用户注册与资料业务。
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private static final Duration ACCESS_TOKEN_VALIDITY = Duration.ofDays(7);
    private final JwtService jwtService;


    /**
     * 创建账号并返回初始用户资料。
     */
    public UserResponse register(RegisterRequest request) {
        String username = request.username().toLowerCase(Locale.ROOT);
        String password = request.password();
        String nickname = request.nickname();
        UUID userId = UuidCreator.getTimeOrderedEpoch();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS); // UTC+0 截断到毫秒

        AppUser user = new AppUser();
        user.setId(userId);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        // 重复约束交给数据库的唯一约束
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            log.info("注册被拒绝，reason=用户名已存在，username={}", username);
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "USERNAME_ALREADY_EXISTS",
                    "用户名已被使用",
                    exception);
        }

        log.info("注册成功，userId={}", userId);

        return new UserResponse(
                userId,
                user.getUsername(),
                user.getNickname(),
                null,
                null,
                now.toInstant(ZoneOffset.UTC),
                now.toInstant(ZoneOffset.UTC));
    }


    /**
     * 验证账号密码，返回访问令牌和本人资料。
     * todo 验证 mp 如何处理软删除
     */
    public LoginResponse login(LoginRequest request) {
        String username = request.username().toLowerCase(Locale.ROOT);

        // 数据库查询用户
        AppUser user = userMapper.selectOne(
                Wrappers.<AppUser>lambdaQuery()
                        .select(
                                AppUser::getId,
                                AppUser::getUsername,
                                AppUser::getPasswordHash,
                                AppUser::getNickname,
                                AppUser::getAvatarFileId,
                                AppUser::getCreatedAt,
                                AppUser::getUpdatedAt)
                        .eq(AppUser::getUsername, username)
                        .isNull(AppUser::getDeletedAt));

        // 验证密码
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("登录失败，username={}，reason=凭据无效", username);
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    "用户名或密码错误");
        }

        // 验证通过
        Instant expiresAt = Instant.now()
                .truncatedTo(ChronoUnit.SECONDS)
                .plus(ACCESS_TOKEN_VALIDITY);

        String accessToken = jwtService.createAccessToken(user.getId(), expiresAt);

        UserResponse userResponse = toUserResponse(user);

        log.info("登录成功，userId={}", user.getId());

        return new LoginResponse(
                accessToken,
                "Bearer",
                expiresAt,
                userResponse);
    }

    /**
     * 获取当前登录用户的最新资料。
     *
     * @param userId 经过令牌验证的用户 ID
     * @return 本人资料
     */
    public UserResponse getCurrentUser(UUID userId) {
        // todo 查询或许要包装一下呢，已经出现两次了
        AppUser user = userMapper.selectOne(
                Wrappers.<AppUser>lambdaQuery()
                        .select(
                                AppUser::getId,
                                AppUser::getUsername,
                                AppUser::getNickname,
                                AppUser::getAvatarFileId,
                                AppUser::getCreatedAt,
                                AppUser::getUpdatedAt)
                        .eq(AppUser::getId, userId)
                        .isNull(AppUser::getDeletedAt));

        if (user == null) {
            log.warn("获取本人资料失败，userId={}，reason=账号不存在或已删除", userId);

            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "账号不存在或已注销，请重新登录");
        }

        return toUserResponse(user);
    }

    /**
     * 将用户记录转换为本人资料。
     */
    private UserResponse toUserResponse(AppUser user) {
        // todo 头像path暂且返回null
        UUID avatarFileId = user.getAvatarFileId();
        String avatarContentPath = null;

        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                avatarFileId,
                avatarContentPath,
                user.getCreatedAt().toInstant(ZoneOffset.UTC),
                user.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
}