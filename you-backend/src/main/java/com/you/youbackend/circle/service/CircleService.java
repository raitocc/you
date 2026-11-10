package com.you.youbackend.circle.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.circle.entity.*;
import com.you.youbackend.circle.mapper.*;
import com.you.youbackend.circle.model.*;
import com.you.youbackend.circle.request.*;
import com.you.youbackend.circle.response.CircleResponse;
import com.you.youbackend.circle.response.CircleListResponse;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

/** 圈子创建、访问控制与展示信息维护，创建的主记录及成员关系共同提交。 */
@Log4j2
@Service
public class CircleService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final UserService userService;
    private final MediaService mediaService;
    private final String defaultAvatarKey;

    /** 注入圈子持久化、公开资料、媒体签名及默认头像配置。 */
    public CircleService(CircleMapper circlesMapper, CircleMemberMapper circleMemberMapper, UserService userService, MediaService mediaService,
                         @Value("${storage.default-circle-avatar-key}") String defaultAvatarKey) {
        this.circleMapper = circlesMapper;
        this.circleMemberMapper = circleMemberMapper;
        this.userService = userService;
        this.mediaService = mediaService;
        this.defaultAvatarKey = defaultAvatarKey;
    }

    /** 创建圈子及创建者成员关系，共同提交或回滚；重复 ID 直接返回 409，不覆盖已有数据。 */
    @Transactional
    public CircleResponse createCircle(CreateCircleRequest request, UUID userId) {
        // 1. 已占用 ID 直接拒绝，不读取已有圈子作为成功结果。
        if (circleMapper.selectIncludingDeleted(request.id()) != null) {
            throw BusinessException.conflict("RESOURCE_CONFLICT", "圈子 ID 已被占用，请勿重复提交");
        }
        // 2. 校验账号和自定义图片后，使用统一 UTC 毫秒时间写入两个记录。
        userService.getCurrentUser(userId);
        if (request.avatarFileId() != null) mediaService.validateOwnedImage(request.avatarFileId(), userId);
        LocalDateTime now = utcNow();
        Circle circle = new Circle();
        circle.setId(request.id());
        circle.setCreatorUserId(userId);
        circle.setName(request.name());
        circle.setDescription(request.description());
        circle.setAvatarFileId(request.avatarFileId());
        circle.setInviteEnabled(true);
        circle.setInviteCode(generateInviteCode());
        circle.setCreatedAt(now);
        circle.setUpdatedAt(now);
        // 3. 唯一约束兜底并发重复提交，冲突异常向外抛出，由事务代理回滚。
        try {
            circleMapper.insert(circle);
            saveCreatorMembership(circle.getId(), userId, now);
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("RESOURCE_CONFLICT", "圈子或成员标识冲突，创建失败", exception);
        }
        // 4. 写入及响应加载共同成功或回滚，不进行自动重试。
        CircleResponse response = loadResponses(List.of(circle), userId).get(0);
        log.info("已写入圈子及创建者成员关系，circleId={}，userId={}", circle.getId(), userId);
        return response;
    }

    /** 查看圈子详情；不存在、解散及非成员统一隐藏为 404。 */
    @Transactional(readOnly = true)
    public CircleResponse getCircle(UUID circleId, UUID userId) {
        // 先检查访问资格，随后批量加载展示依赖，转换阶段不查询数据库。
        Circle circle = requireAccessibleCircle(circleId, userId);
        return loadResponses(List.of(circle), userId).get(0);
    }

    /** 按成员关系创建时间和 ID 分页，仅返回有效圈子的 ID、名称和头像。 */
    @Transactional(readOnly = true)
    public ResultPage<CircleListResponse, CircleListCursor> listMyCircles(UUID userId, PageRequest<CircleListCursor> request) {
        // 多读一条判断后续页，游标始终使用成员关系的位置。
        CircleListCursor cursor = request.cursor();
        List<CircleListRow> rows = cursor == null
                ? circleMemberMapper.selectFirstPage(userId, request.limit() + 1)
                : circleMemberMapper.selectNextPage(userId, LocalDateTime.ofInstant(cursor.joinedAt(), ZoneOffset.UTC),
                        cursor.id(), request.limit() + 1);
        boolean hasMore = rows.size() > request.limit();
        List<CircleListRow> page = hasMore ? rows.subList(0, request.limit()) : rows;
        CircleListCursor next = null;
        if (hasMore) {
            CircleListRow last = page.get(page.size() - 1);
            next = new CircleListCursor(last.getJoinedAt().toInstant(ZoneOffset.UTC), last.getMembershipId());
        }
        // 列表只补齐头像，创建者资料及成员数由详情接口加载。
        return new ResultPage<>(loadListResponses(page), next, hasMore);
    }

    /** 创建者全量更新展示信息；null 清空介绍和头像，不删除媒体文件。 */
    @Transactional
    public CircleResponse updateCircle(UUID circleId, UpdateCircleRequest request, UUID userId) {
        // 先按成员资格隐藏不可访问目标，再拒绝普通成员编辑。
        Circle circle = requireAccessibleCircle(circleId, userId);
        if (!userId.equals(circle.getCreatorUserId())) throw BusinessException.forbidden("只有圈子创建者可以编辑");
        if (request.avatarFileId() != null) mediaService.validateOwnedImage(request.avatarFileId(), userId);
        circle.setName(request.name());
        circle.setDescription(request.description());
        circle.setAvatarFileId(request.avatarFileId());
        circle.setUpdatedAt(utcNow());
        if (circleMapper.updateContent(circle) == 0) throw unavailable();
        log.info("已写入圈子展示信息，circleId={}，userId={}", circleId, userId);
        return loadResponses(List.of(circle), userId).get(0);
    }

    /** 查有效圈子及当前成员关系，调用者随后可以加载圈子展示信息。 */
    private Circle requireAccessibleCircle(UUID circleId, UUID userId) {
        Circle circle = circleMapper.selectById(circleId);
        if (circle == null || !circleMemberMapper.isActiveMember(circleId, userId)) throw unavailable();
        return circle;
    }

    /** 统一不可访问提示，防止通过错误响应判断圈子存在性。 */
    private BusinessException unavailable() {
        return BusinessException.notFound("CIRCLE_NOT_FOUND", "圈子不存在或不可访问");
    }

    /** 为当前页批量读取头像并组装摘要，不加载创建者资料和成员数量。 */
    private List<CircleListResponse> loadListResponses(List<CircleListRow> page) {
        // 空页不访问媒体服务，实际展示的图片去重后批量读取。
        if (page.isEmpty()) return List.of();
        var avatarIds = page.stream().map(CircleListRow::getAvatarFileId)
                .filter(Objects::nonNull).distinct().toList();
        var avatars = mediaService.getReadUrls(avatarIds);
        String defaultUrl = mediaService.createReadUrl(defaultAvatarKey);
        // 响应仅保留列表必需字段，未设置或失效的图片统一使用默认头像。
        return page.stream().map(circle -> new CircleListResponse(circle.getId(), circle.getName(),
                circle.getAvatarFileId() == null ? defaultUrl
                        : avatars.getOrDefault(circle.getAvatarFileId(), defaultUrl))).toList();
    }

    /** 为创建、详情及编辑结果读取完整展示依赖，头像失效时保留 ID 并使用默认地址。 */
    private List<CircleResponse> loadResponses(List<? extends Circle> page, UUID userId) {
        // 没有圈子就直接返回空列表
        if (page.isEmpty()) return List.of();
        // 按实际展示范围批量查询创建者、成员数及图片，不在响应映射中访问数据库。
        var profiles = userService.getPublicUsers(page.stream().map(Circle::getCreatorUserId).distinct().toList());
        var counts = circleMemberMapper.countActiveMembers(page.stream().map(Circle::getId).toList()).stream()
                .collect(Collectors.toMap(CircleMemberCount::circleId, CircleMemberCount::memberCount));
        var avatars = mediaService.getReadUrls(page.stream().map(Circle::getAvatarFileId).filter(Objects::nonNull).distinct().toList());
        String defaultUrl = mediaService.createReadUrl(defaultAvatarKey);
        return page.stream().map(circle -> {
            PublicUserResponse creator = profiles.get(circle.getCreatorUserId());
            if (creator == null) throw new IllegalStateException("圈子创建者公开资料缺失：" + circle.getId());
            String avatar = circle.getAvatarFileId() == null ? defaultUrl
                    : avatars.getOrDefault(circle.getAvatarFileId(), defaultUrl);
            return toResponse(circle, creator, counts.getOrDefault(circle.getId(), 0L), userId, avatar);
        }).toList();
    }

    /** 仅将已加载数据映射为响应，数据库时间转换为 UTC Instant。 */
    private CircleResponse toResponse(Circle circle, PublicUserResponse creator, long count, UUID userId, String avatar) {
        return new CircleResponse(circle.getId(), circle.getName(), circle.getDescription(), creator, count,
                userId.equals(circle.getCreatorUserId()), circle.getInviteEnabled(),
                circle.getCreatedAt().toInstant(ZoneOffset.UTC), circle.getUpdatedAt().toInstant(ZoneOffset.UTC),
                circle.getAvatarFileId(), avatar);
    }

    /** 新建创建者成员关系，必须与主记录处于相同事务。 */
    private void saveCreatorMembership(UUID circleId, UUID userId, LocalDateTime now) {
        CircleMember member = new CircleMember();
        member.setId(UuidCreator.getTimeOrderedEpoch());
        member.setCircleId(circleId);
        member.setUserId(userId);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        circleMemberMapper.insert(member);
    }

    /** 生成内部长期邀请码，唯一冲突直接拒绝创建并回滚，不自动重试。 */
    private String generateInviteCode() {
        StringBuilder code = new StringBuilder(16);
        for (int index = 0; index < 16; index++) code.append(CODE_CHARACTERS.charAt(RANDOM.nextInt(CODE_CHARACTERS.length())));
        return code.toString();
    }

    /** 写入时间统一使用 UTC 毫秒，匹配数据库 DATETIME(3)。 */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
}
