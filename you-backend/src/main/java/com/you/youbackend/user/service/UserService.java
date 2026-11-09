package com.you.youbackend.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.user.mapper.UserMapper;
import com.you.youbackend.user.model.AppUser;
import com.you.youbackend.user.request.LoginRequest;
import com.you.youbackend.user.request.RegisterRequest;
import com.you.youbackend.user.request.UpdateProfileRequest;
import com.you.youbackend.user.response.LoginResponse;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.response.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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
    private final MediaService mediaService;


    /**
     * 创建账号并返回初始用户资料。
     */
    public UserResponse register(RegisterRequest request) {
        String username = request.username().toLowerCase(Locale.ROOT);
        String password = request.password();
        String nickname = request.nickname();
        UUID userId = UuidCreator.getTimeOrderedEpoch(); // uuid v7
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
            throw BusinessException.conflict("USERNAME_ALREADY_EXISTS", "用户名已被使用", exception);
        }

        log.info("注册成功，userId={}", userId);

        return toUserResponse(user);
    }


    /**
     * 验证账号密码，返回访问令牌和本人资料。
     */
    public LoginResponse login(LoginRequest request) {
        String username = request.username().toLowerCase(Locale.ROOT);

        // 数据库查询用户
        AppUser user = findUserByUsername(username);

        // 验证密码
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("登录失败，username={}，reason=凭据无效", username);
            throw BusinessException.unauthorized("INVALID_CREDENTIALS", "用户名或密码错误");
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
     * 按用户名查询登录所需的账号资料和密码散列。
     */
    private AppUser findUserByUsername(String username) {
        return userMapper.selectOne(
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
        );
    }

    /**
     * 获取当前登录用户的最新资料。
     *
     * @param userId 经过令牌验证的用户 ID
     * @return 本人资料
     */
    public UserResponse getCurrentUser(UUID userId) {
        AppUser user = findUserById(userId);

        if (user == null) {
            log.warn("获取本人资料失败，userId={}，reason=账号不存在或已删除", userId);

            throw BusinessException.unauthorized("账号不存在或已注销，请重新登录");
        }

        return toUserResponse(user);
    }

    /**
     * 查询有效用户的公开资料，供同行等业务展示成员身份。
     * @param userId 用户 ID，调用方负责判断具体业务中的访问资格
     * @return 用户 ID、昵称及头像临时签名 URL
     * @throws BusinessException 账号不存在或已注销时返回 401
     */
    public PublicUserResponse getPublicUser(UUID userId) {
        // 1. 复用有效用户查询，软删除记录由全局配置过滤。
        AppUser user = findUserById(userId);
        if (user == null) {
            log.warn("读取用户公开资料被拒绝，userId={}，reason=账号不存在或已删除", userId);
            throw BusinessException.unauthorized("账号不存在或已注销，请重新登录");
        }
        // 2. 头像为公开资料，沿用媒体读取签名；响应只包含展示所需字段。
        String avatarUrl = user.getAvatarFileId() == null
                ? null
                : mediaService.getReadUrl(user.getAvatarFileId());
        return new PublicUserResponse(user.getId(), user.getNickname(), avatarUrl);
    }

    /**
     * 批量加载公开资料与头像，供同行候选等列表展示。
     * @param userIds 待展示的用户 ID 集合，允许为空
     * @return 以用户 ID 为键的公开资料映射，未查得的用户省略
     */
    public Map<UUID, PublicUserResponse> getPublicUsers(Collection<UUID> userIds) {
        // 1. 批量查询展示字段，空列表直接结束。
        if (userIds.isEmpty()) {
            return Map.of();
        }
        var profiles = userMapper.selectPublicProfiles(userIds);
        // 2. 头像来自用户公开资料，批量读取文件后本地签名，避免逐项查库。
        var avatarIds = profiles.stream().map(AppUser::getAvatarFileId)
                .filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> avatarUrls = mediaService.getReadUrls(avatarIds);
        return profiles.stream().collect(Collectors.toMap(AppUser::getId,
                user -> new PublicUserResponse(user.getId(), user.getNickname(),
                        user.getAvatarFileId() == null ? null : avatarUrls.get(user.getAvatarFileId()))));
    }

    /**
     * 按用户 ID 查询本人资料所需字段。
     */
    private AppUser findUserById(UUID userId) {
        return userMapper.selectOne(
                Wrappers.<AppUser>lambdaQuery()
                        .select(
                                AppUser::getId,
                                AppUser::getUsername,
                                AppUser::getNickname,
                                AppUser::getAvatarFileId,
                                AppUser::getCreatedAt,
                                AppUser::getUpdatedAt)
                        .eq(AppUser::getId, userId)
        );
    }

    /**
     * 更新本人昵称和头像，返回最新资料。
     *
     * @param userId 当前登录用户 ID
     * @param request 全量可编辑资料
     * @return 更新后的本人资料
     */
    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        UUID avatarFileId = request.avatarFileId();

        if (avatarFileId != null) {
            mediaService.validateOwnedImage(avatarFileId, userId);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);

        int affectedRows = userMapper.update(
                null,
                Wrappers.<AppUser>lambdaUpdate()
                        .eq(AppUser::getId, userId)
                        .set(AppUser::getNickname, request.nickname())
                        .set(AppUser::getAvatarFileId, avatarFileId)
                        .set(AppUser::getUpdatedAt, now));

        if (affectedRows == 0) {
            log.warn("更新本人资料被拒绝，userId={}，reason=账号不存在或已删除", userId);
            throw BusinessException.unauthorized("账号不存在或已注销，请重新登录");
        }

        log.info("更新本人资料，userId={}，avatarFileId={}", userId, avatarFileId);

        return getCurrentUser(userId);
    }

    /**
     * 将用户记录转换为本人资料。
     */
    private UserResponse toUserResponse(AppUser user) {
        UUID avatarFileId = user.getAvatarFileId();
        String avatarUrl = avatarFileId == null
                ? null
                : mediaService.getReadUrl(avatarFileId);

        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                avatarFileId,
                avatarUrl,
                user.getCreatedAt().toInstant(ZoneOffset.UTC),
                user.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
}
