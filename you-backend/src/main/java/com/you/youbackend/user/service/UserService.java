package com.you.youbackend.user.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.util.TimeUtils;
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
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.Locale;
import java.util.UUID;

/**
 * 账号注册、登录和本人资料维护。
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MediaService mediaService;

    /** 访问令牌有效期，登录时签发给客户端。 */
    private static final Duration ACCESS_TOKEN_VALIDITY = Duration.ofDays(7);

    /**
     * 注册新账号：用户名入库前转小写，密码只存散列，返回初始本人资料。
     *
     * <p>流程：用户名转小写 → 密码编码成散列 → 组装账号主记录入库（ID 为 UUIDv7）→ 拼本人资料。
     * 副作用：往 app_user 表插一行，跟随外层事务。</p>
     *
     * @param request 已由 MVC 校验过的注册请求；昵称已去首尾空白
     * @return 新账号的本人资料，头像未设置时地址为 null
     * @throws BusinessException 用户名已被占用（409）
     */
    public UserResponse register(RegisterRequest request) {
        // 1. 用户名转小写，注册和登录都按小写比对
        String username = request.username().toLowerCase(Locale.ROOT);
        // 2. 密码编码成散列，明文不落库
        String passwordHash = passwordEncoder.encode(request.password());
        // 3. 组装账号主记录并入库，用户名重复交给数据库唯一约束判断
        Instant savedAt = TimeUtils.now();
        AppUser user = saveNewUser(username, passwordHash, request.nickname(), savedAt);
        log.info("注册成功，userId={}", user.getId());
        // 4. 拼本人资料返回
        return convertToUserResponse(user);
    }

    /**
     * 校验账号密码，登录成功签发访问令牌并返回本人资料。
     *
     * <p>流程：用户名转小写查账号 → 比对密码散列 → 签发 7 天有效的访问令牌 → 拼登录响应。
     * 只读查询，无副作用（令牌签发不落库）。</p>
     *
     * @param request 已由 MVC 校验过的登录请求
     * @return 访问令牌（Bearer）、过期时间和本人资料
     * @throws BusinessException 用户名或密码不对（401）；不区分“账号不存在”和“密码错误”，避免暴露账号是否存在
     */
    public LoginResponse login(LoginRequest request) {
        // 1. 用户名转小写后查账号，密码散列只在本次比对用，禁止外传
        String username = request.username().toLowerCase(Locale.ROOT);
        AppUser user = userMapper.selectWithPasswordHashByUsername(username);
        // 2. 比对密码散列；账号不存在和密码错误走同一个 401
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("登录失败，username={}，reason=凭据无效", username);
            throw BusinessException.unauthorized("INVALID_CREDENTIALS", "用户名或密码错误");
        }
        // 3. 签发访问令牌，过期时间截断到秒（JWT 过期时间按秒记）
        Instant expiresAt = Instant.now()
                .truncatedTo(ChronoUnit.SECONDS)
                .plus(ACCESS_TOKEN_VALIDITY);
        String accessToken = jwtService.createAccessToken(user.getId(), expiresAt);
        // 4. 拼登录响应
        log.info("登录成功，userId={}", user.getId());
        return new LoginResponse(
                accessToken,
                "Bearer",
                expiresAt,
                convertToUserResponse(user));
    }

    /**
     * 覆写指定用户的密码散列，改密后旧密码立即失效。
     *
     * <p>副作用：更新 app_user 的 password_hash，跟随外层事务。</p>
     *
     * @param userId      目标用户 ID
     * @param newPassword 新密码明文，本方法负责编码成散列
     */
    public void resetPassword(UUID userId, String newPassword) {
        userMapper.updatePasswordHash(userId, passwordEncoder.encode(newPassword));
    }

    /**
     * 查本人最新资料，账号没了就当登录失效处理。
     *
     * <p>流程：查有效账号 → 头像签临时读取地址 → 拼本人资料。
     * 只读查询，无副作用。</p>
     *
     * @param userId 经过令牌验证的用户 ID
     * @return 本人资料，头像未设置时地址为 null
     * @throws BusinessException 账号不存在或已删除（401，视同登录失效）
     */
    public UserResponse getCurrentUser(UUID userId) {
        // 1. 查有效账号，查不到按登录失效处理
        AppUser user = getActiveUser(userId);
        // 2. 拼本人资料
        return convertToUserResponse(user);
    }

    /**
     * 查单个有效用户的公开资料，供同行等业务展示成员身份。
     *
     * <p>流程：查有效账号 → 头像签临时读取地址 → 拼公开资料。
     * 只读查询，无副作用。</p>
     *
     * @param userId 用户 ID，调用方负责判断具体业务中的访问资格
     * @return 用户 ID、昵称及头像临时签名 URL
     * @throws BusinessException 账号不存在或已注销（401）
     */
    public PublicUserResponse getPublicUser(UUID userId) {
        // 1. 复用有效账号查询，软删除记录由全局配置过滤
        AppUser user = getActiveUser(userId);
        // 2. 头像为公开资料，沿用媒体读取签名；响应只包含展示所需字段
        String avatarUrl = user.getAvatarFileId() == null
                ? null
                : mediaService.getReadUrl(user.getAvatarFileId());
        return convertToPublicUserResponse(user, avatarUrl);
    }

    /**
     * 批量查有效用户的公开资料，供同行候选等列表展示。
     *
     * <p>流程：空集合直接返回 → 批量查公开字段 → 批量签头像地址 → 拼映射。
     * 只读查询，无副作用。</p>
     *
     * @param userIds 待展示的用户 ID 集合，允许为空
     * @return 以用户 ID 为键的公开资料映射，未查得的用户省略
     */
    public Map<UUID, PublicUserResponse> getPublicUsers(Collection<UUID> userIds) {
        // 1. 批量查公开字段，空列表直接结束
        if (userIds.isEmpty()) {
            return Map.of();
        }
        var profiles = userMapper.selectPublicProfiles(userIds);
        // 2. 头像批量签地址，避免逐项查库
        var avatarIds = profiles.stream().map(AppUser::getAvatarFileId)
                .filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> avatarUrls = mediaService.getReadUrls(avatarIds);
        // 3. 逐条拼公开资料
        return profiles.stream().collect(Collectors.toMap(AppUser::getId,
                user -> convertToPublicUserResponse(user,
                        user.getAvatarFileId() == null ? null : avatarUrls.get(user.getAvatarFileId()))));
    }

    /**
     * 全量更新本人昵称和头像，返回更新后的资料。
     *
     * <p>流程：头像非空时校验是本人可用图片 → 覆盖昵称和头像（头像传 null 即移除）→ 重新读一份资料返回。
     * 副作用：更新 app_user 的昵称、头像和修改时间，跟随外层事务。</p>
     *
     * @param userId  当前登录用户 ID
     * @param request 全量可编辑资料；nickname 为必填，avatarFileId 传 null 移除头像
     * @return 更新后的本人资料
     * @throws BusinessException 账号不存在或已删除（401），头像不是本人可用图片（400）
     */
    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        // 1. 头像非空时校验是本人可用图片
        UUID avatarFileId = request.avatarFileId();
        if (avatarFileId != null) {
            mediaService.validateOwnedImage(avatarFileId, userId);
        }
        // 2. 覆盖昵称和头像，统一记本次修改时间
        Instant savedAt = TimeUtils.now();
        int affectedRows = userMapper.updateProfile(userId, request.nickname(), avatarFileId, savedAt);
        // 3. 一行都没更新到，当登录失效处理
        if (affectedRows == 0) {
            log.warn("更新本人资料被拒绝，userId={}，reason=账号不存在或已删除", userId);
            throw BusinessException.unauthorized("账号不存在或已注销，请重新登录");
        }
        log.info("更新本人资料，userId={}，avatarFileId={}", userId, avatarFileId);
        // 4. 重新读一份资料返回
        return getCurrentUser(userId);
    }

    /**
     * 组装并插入新账号主记录：服务端生成 UUIDv7 ID，审计时间用统一的保存时间。
     *
     * <p>副作用：往 app_user 表插一行，跟随外层事务。</p>
     *
     * @param username     已转小写的用户名
     * @param passwordHash 已编码的密码散列，不是明文
     * @param nickname     已去首尾空白的昵称
     * @param savedAt      本次写入时间，UTC，同时给 createdAt 和 updatedAt
     * @return 插入后的账号实体，供响应组装复用
     * @throws BusinessException 用户名已被占用（409，撞用户名唯一约束）
     */
    private AppUser saveNewUser(String username, String passwordHash, String nickname, Instant savedAt) {
        // 1. 服务端生成账号身份，审计时间用本次统一时间
        AppUser user = new AppUser();
        user.setId(UuidCreator.getTimeOrderedEpoch());
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setNickname(nickname);
        user.setCreatedAt(savedAt);
        user.setUpdatedAt(savedAt);
        // 2. 入库；用户名是否重复由数据库唯一约束兜底，避免先查后插的并发漏洞
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            log.info("注册被拒绝，reason=用户名已存在，username={}", username);
            throw BusinessException.conflict("USERNAME_ALREADY_EXISTS", "用户名已被使用", exception);
        }
        return user;
    }

    /**
     * 按用户 ID 查有效账号（不含密码散列），查不到就当登录失效处理。
     *
     * <p>流程：按 ID 查账号 → 没查到记 warn 日志并抛未授权异常。
     * 只读查询，无副作用。</p>
     *
     * @param userId 用户 ID
     * @return 有效账号实体，字段不含密码散列
     * @throws BusinessException 账号不存在或已删除（401，视同登录失效）
     */
    private AppUser getActiveUser(UUID userId) {
        AppUser user = userMapper.selectProfileById(userId);
        if (user == null) {
            log.warn("用户查询未命中，userId={}，reason=账号不存在或已删除", userId);
            throw BusinessException.unauthorized("账号不存在或已注销，请重新登录");
        }
        return user;
    }

    /**
     * 把账号实体拼成本人资料，头像签临时读取地址。不改传入实体。
     *
     * <p>只读查询，无副作用。</p>
     *
     * @param user 已通过查询拿到的账号实体，时间字段为 UTC
     * @return 本人资料，头像未设置时地址为 null
     */
    private UserResponse convertToUserResponse(AppUser user) {
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
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    /**
     * 拼单条公开资料，不查库，头像地址由调用方算好传进来。
     *
     * @param user      账号实体，用到 ID、昵称和头像文件 ID
     * @param avatarUrl 已签名的头像地址，无头像或失效时为 null
     * @return 公开资料响应
     */
    private PublicUserResponse convertToPublicUserResponse(AppUser user, String avatarUrl) {
        return new PublicUserResponse(user.getId(), user.getNickname(), avatarUrl);
    }
}
