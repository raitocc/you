package com.you.youbackend.circle.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.circle.response.*;
import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.mapper.*;
import com.you.youbackend.album.model.AlbumMomentCount;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.response.MomentResponse;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.circle.entity.*;
import com.you.youbackend.circle.mapper.*;
import com.you.youbackend.circle.model.*;
import com.you.youbackend.circle.request.*;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/** 圈子创建、访问控制、展示信息与分享引用维护；各写入入口明确事务边界。 */
@Log4j2
/** 处理圈子资料、成员访问和圈子分享。 */
@Service
public class CircleService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final UserService userService;
    private final MediaService mediaService;
    private final CircleShareMapper circleShareMapper;
    private final MomentMapper momentMapper;
    private final AlbumMapper albumMapper;
    private final AlbumMomentMapper albumMomentMapper;
    private final MomentService momentService;
    private final String defaultAvatarKey;

    /** 注入圈子与分享持久化、内容加载、公开资料、媒体签名及默认头像配置。 */
    public CircleService(CircleMapper circlesMapper, CircleMemberMapper circleMemberMapper, UserService userService, MediaService mediaService,
                         CircleShareMapper circleShareMapper, MomentMapper momentMapper, AlbumMapper albumMapper,
                         AlbumMomentMapper albumMomentMapper, MomentService momentService,
                         @Value("${storage.default-circle-avatar-key}") String defaultAvatarKey) {
        this.circleMapper = circlesMapper;
        this.circleMemberMapper = circleMemberMapper;
        this.userService = userService;
        this.mediaService = mediaService;
        this.defaultAvatarKey = defaultAvatarKey;
        this.circleShareMapper = circleShareMapper;
        this.momentMapper = momentMapper;
        this.albumMapper = albumMapper;
        this.albumMomentMapper = albumMomentMapper;
        this.momentService = momentService;
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

    /** 分享到多个圈子：所有目标都通过检查后才写入，写入冲突时整批回滚并返回 409。 */
    @Transactional
    public void shareToCircles(CreateCircleSharesRequest request, UUID userId) {
        // 1. 检查原内容是否存在，以及是否属于当前用户。
        requireOwnedObject(request, userId);
        // 2. 确认所有目标圈子都未解散，且当前用户仍是每个圈子的成员。
        if (circleMemberMapper.selectActiveCircleIds(userId, request.circleIds()).size() != request.circleIds().size()) {
            throw BusinessException.notFound("CIRCLE_NOT_FOUND", "目标圈子不存在或不可访问");
        }
        // 3. 查出这条内容在目标圈子里的分享，包括已撤回的记录，再按圈子 ID 查找。
        Map<UUID, CircleShare> history = circleShareMapper.selectHistory(request.objectType(), request.objectId(), request.circleIds())
                .stream().collect(Collectors.toMap(CircleShare::getCircleId, Function.identity()));
        LocalDateTime now = utcNow();
        List<CircleShare> additions = new ArrayList<>();
        List<UUID> restores = new ArrayList<>();
        // 4. 从未分享过就新增，已撤回就恢复，仍有效就保留原记录。
        for (UUID circleId : request.circleIds()) {
            CircleShare row = history.get(circleId);
            if (row == null) {
                additions.add(newShare(request, circleId, userId, now));
            } else if (row.getDeletedAt() != null) {
                restores.add(row.getId());
            }
        }
        // 5. 在同一事务里写入新增和恢复的记录。
        persistChanges(additions, restores, userId, now);
        log.info("已写入圈子分享，userId={}，targetCount={}，addedCount={}，restoredCount={}",
                userId, request.circleIds().size(), additions.size(), restores.size());
    }

    /** 分页浏览圈子里的分享：只有当前成员可以查看。按分享时间从新到旧排列，时间相同按分享 ID 倒序。 */
    @Transactional(readOnly = true)
    public ResultPage<CircleShareFeedResponse, CircleShareCursor> browseShares(
            UUID circleId,
            UUID userId,
            PageRequest<CircleShareCursor> request) {
        // 1. 检查当前用户的成员身份，再查仍可查看的分享。
        // 1.1 检查圈子是否存在，以及用户是否是圈子成员
        Circle circle = requireAccessibleCircle(circleId, userId);
        // 1.2 游标分页查询
        CircleShareCursor cursor = request.cursor();
        // cusor==null 为第一页，获取前 limit + 1 条数据；
        // cusor!=null 不为第一页，基于游标 cursor(sharedAt,id) 查询接下来的 limit + 1 条数据
        List<CircleShare> rows = cursor == null ? circleShareMapper.selectFirstPage(circleId, request.limit() + 1)
                : circleShareMapper.selectNextPage(circleId, cursorTime(cursor.sharedAt()), cursor.id(), request.limit() + 1);
        // 如果查出来的实际行数大于要求的 limit，说明后面还有数据
        boolean hasMore = rows.size() > request.limit();
        // 实际查到的页 page
        List<CircleShare> page = hasMore ? rows.subList(0, request.limit()) : rows;
        // 2. 多查一条判断是否还有下一页，只为本页返回的记录加载内容和图片地址。
        // 2.1 只对当前页筛选出来的有效记录（page）加载详细内容、图片地址、以及其他展示层所需的关联数据
        List<CircleShareFeedResponse> items = loadFeed(page, circle.getCreatorUserId(), userId);
        CircleShareCursor next = null;
        if (hasMore) {
            CircleShare last = page.get(page.size() - 1);
            next = new CircleShareCursor(last.getSharedAt().toInstant(ZoneOffset.UTC), last.getId());
        }
        return new ResultPage<>(items, next, hasMore);
    }

    /** 分页查看已分享时光里仍有效的瞬间：按发生时间从早到晚排列，时间相同按瞬间 ID 正序。 */
    @Transactional(readOnly = true)
    public ResultPage<MomentResponse, CircleShareMomentCursor> browseSharedAlbumMoments(
            UUID circleId,
            UUID shareId,
            UUID userId,
            PageRequest<CircleShareMomentCursor> request) {
        // 1. 检查用户是否是成员、分享是否在当前圈子且仍有效，以及分享的内容是否为时光。
        requireAccessibleCircle(circleId, userId);
        CircleShare share = circleShareMapper.selectAccessible(circleId, shareId);
        if (share == null) throw shareUnavailable();
        if (share.getObjectType() != CircleShareObjectType.album) {
            throw BusinessException.conflict("SHARE_TYPE_MISMATCH", "该分享不是时光");
        }
        // 2. 查仍在该时光里且未被删除的瞬间，多查一条判断是否还有下一页。
        CircleShareMomentCursor cursor = request.cursor();
        List<Moment> rows = cursor == null ? albumMomentMapper.selectFirstSharedPage(share.getObjectId(), request.limit() + 1)
                : albumMomentMapper.selectNextSharedPage(share.getObjectId(), cursorTime(cursor.memoryTime()), cursor.id(), request.limit() + 1);
        boolean hasMore = rows.size() > request.limit();
        List<Moment> page = hasMore ? rows.subList(0, request.limit()) : rows;
        CircleShareMomentCursor next = null;
        if (hasMore) {
            Moment last = page.get(page.size() - 1);
            next = new CircleShareMomentCursor(last.getMemoryTime().toInstant(ZoneOffset.UTC), last.getId());
        }
        // 3. 为本页瞬间补齐图片、标签和作者资料。
        return new ResultPage<>(momentService.loadAuthorizedResponses(page), next, hasMore);
    }

    /** 撤回当前圈子里的这条分享：有权限时重复撤回也返回成功。 */
    @Transactional
    public void withdrawShare(UUID circleId, UUID shareId, UUID userId) {
        // 1. 先检查成员身份，再查这条分享及其撤回权限。查询包含已撤回的记录。
        Circle circle = requireAccessibleCircle(circleId, userId);
        CircleShare share = circleShareMapper.selectHistoryById(circleId, shareId);
        if (share == null) throw shareUnavailable();
        if (!canWithdraw(share, circle.getCreatorUserId(), userId)) {
            log.warn("撤回分享被拒绝，circleId={}，shareId={}，userId={}", circleId, shareId, userId);
            throw BusinessException.forbidden("只有分享者或圈子创建者可以撤回分享");
        }
        // 2. 已撤回就直接结束，否则只为当前圈子的这条分享写入撤回时间。
        if (share.getDeletedAt() != null) return;
        circleShareMapper.withdraw(shareId, utcNow());
        log.info("已写入圈子分享撤回，circleId={}，shareId={}，userId={}", circleId, shareId, userId);
    }

    /** 返回当前用户可访问的圈子：圈子不存在、已解散或用户已不是成员时返回 404。 */
    private Circle requireAccessibleCircle(UUID circleId, UUID userId) {
        Circle circle = circleMapper.selectById(circleId);
        if (circle == null || !circleMemberMapper.isActiveMember(circleId, userId)) throw unavailable();
        return circle;
    }

    /** 生成圈子不可访问时返回的 404 错误。 */
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

    /** 取当前 UTC 时间，保留到毫秒，与数据库 DATETIME(3) 一致。 */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    /** 检查要分享的瞬间或时光是否存在并属于当前用户。找不到返回 404，属于他人返回 403。 */
    private void requireOwnedObject(CreateCircleSharesRequest request, UUID userId) {
        // 按分享类型查瞬间作者或时光所有者。
        UUID owner;
        if (request.objectType() == CircleShareObjectType.moment) {
            Moment moment = momentMapper.selectById(request.objectId());
            if (moment == null) throw BusinessException.notFound("MOMENT_NOT_FOUND", "瞬间不存在或已删除");
            owner = moment.getAuthorUserId();
        } else {
            Album album = albumMapper.selectById(request.objectId());
            if (album == null) throw BusinessException.notFound("ALBUM_NOT_FOUND", "时光不存在或已删除");
            owner = album.getOwnerUserId();
        }
        if (!userId.equals(owner)) throw BusinessException.forbidden("只能分享本人发布的瞬间或时光");
    }

    /** 为一个目标圈子创建待写入的分享记录，保存原内容的类型和 ID。 */
    private CircleShare newShare(CreateCircleSharesRequest request, UUID circleId, UUID userId, LocalDateTime now) {
        // 每条新分享生成自己的 ID，恢复时仍用原来的 ID。
        CircleShare row = new CircleShare();
        row.setId(UuidCreator.getTimeOrderedEpoch());
        row.setCircleId(circleId);
        row.setObjectType(request.objectType());
        row.setObjectId(request.objectId());
        row.setSharerUserId(userId);
        row.setSharedAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 批量插入新分享并恢复已撤回的分享。遇到唯一约束冲突或恢复条数不足时返回 409，由外层事务回滚。 */
    private void persistChanges(List<CircleShare> additions, List<UUID> restores, UUID userId, LocalDateTime now) {
        // 有待新增或待恢复的记录时才执行对应的 SQL。
        try {
            if (!additions.isEmpty()) circleShareMapper.insertShares(additions);
            if (!restores.isEmpty() && circleShareMapper.restoreShares(restores, userId, now) != restores.size()) {
                throw BusinessException.conflict("RESOURCE_CONFLICT", "分享状态已变化，请重新操作");
            }
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("RESOURCE_CONFLICT", "分享记录冲突，请重新操作", exception);
        }
    }

    /** 为本页分享组装列表条目：返回顺序与查询结果一致。 */
    private List<CircleShareFeedResponse> loadFeed(List<CircleShare> page, UUID creatorId, UUID userId) {
        // 1. 分别加载完整瞬间、时光卡片和分享者公开资料。
        if (page.isEmpty()) return List.of();
        // 将当前页面的分享记录按类型拆分瞬间 ID 列表 (momentIds)、时光卡片 ID 列表 (albumIds)
        List<UUID> momentIds = objectIds(page, CircleShareObjectType.moment);
        List<UUID> albumIds = objectIds(page, CircleShareObjectType.album);
        // 批量加载瞬间详情
        Map<UUID, MomentResponse> momentResponses = momentIds.isEmpty() ? Map.of()
                : momentService.loadAuthorizedResponses(momentMapper.selectByIds(momentIds)).stream()
                .collect(Collectors.toMap(MomentResponse::id, Function.identity()));
        // 批量加载时光卡片详情
        Map<UUID, CircleSharedAlbumResponse> albumResponses = loadAlbumCards(albumIds);
        // 批量加载分享者的公开信息
        Map<UUID, PublicUserResponse> profiles = loadSharers(page);
        // 2. 按分享顺序组装条目。如果找不到对应的瞬间或时光，返回 404。
        // stream().map() 对分页里的每一条记录应用 toFeedResponse 方法，最终返回 List<CircleShareFeedResponse>
        return page.stream()
                .map(row -> toFeedResponse(row, creatorId, userId, profiles, momentResponses, albumResponses))
                .toList();
    }

    /** 将单条分享记录转换为对应的 Feed 响应条目。 */
    private CircleShareFeedResponse toFeedResponse(
            // 分享表里的一行数据
            CircleShare row,
            UUID creatorId,
            UUID userId,
            Map<UUID, PublicUserResponse> profiles,
            Map<UUID, MomentResponse> momentResponses,
            Map<UUID, CircleSharedAlbumResponse> albumResponses) {

        // 获取并校验分享者公开资料
        PublicUserResponse sharer = profiles.get(row.getSharerUserId());
        if (sharer == null) {
            throw new IllegalStateException("分享者公开资料缺失：" + row.getId());
        }

        // 获取当前分享记录里的 moment（如果是 moment）
        MomentResponse moment = momentResponses.get(row.getObjectId());
        // 去除当前分享记录里的 album（如果是 album）
        CircleSharedAlbumResponse album = albumResponses.get(row.getObjectId());

        boolean isMoment = row.getObjectType() == CircleShareObjectType.moment;
        boolean isAlbum = row.getObjectType() == CircleShareObjectType.album;

        if ((isMoment && moment == null) || (isAlbum && album == null)) {
            throw shareUnavailable();
        }

        // 直接构造 Feed 响应对象
        return new CircleShareFeedResponse(
                row.getId(),                                                    // 关系 ID
                row.getCircleId(),                                              // 圈子 ID
                row.getObjectType(),                                            // 对象类型
                row.getObjectId(),                                              // 对象 ID
                sharer,                                                         // 分享者公开资料
                row.getSharedAt().toInstant(ZoneOffset.UTC),                    // 分享时间
                row.getUpdatedAt().toInstant(ZoneOffset.UTC),                   // 修改时间
                canWithdraw(row, creatorId, userId),                            // 撤回权限
                isMoment ? moment : null,                                       // 瞬间详情
                isAlbum ? album : null                                          // 时光卡片详情
        );


    }

    /** 取出本页指定类型的原内容 ID，并去掉重复 ID。 */
    private List<UUID> objectIds(List<CircleShare> page, CircleShareObjectType type) {
        return page.stream().filter(row -> row.getObjectType() == type).map(CircleShare::getObjectId).distinct().toList();
    }

    /** 为已通过权限检查的时光组装卡片，返回以时光 ID 为键的 Map。 */
    private Map<UUID, CircleSharedAlbumResponse> loadAlbumCards(List<UUID> ids) {
        // 1. 批量查询时光、当前瞬间数量和可用的封面文件。
        if (ids.isEmpty()) return Map.of();
        List<Album> rows = albumMapper.selectByIds(ids);
        Map<UUID, Long> counts = albumMomentMapper.countSharedAlbumMoments(ids).stream()
                .collect(Collectors.toMap(AlbumMomentCount::albumId, AlbumMomentCount::momentCount));
        Map<UUID, String> keys = mediaService.findImageKeys(rows.stream().map(Album::getCoverFileId).filter(Objects::nonNull).distinct().toList());
        // 2. 生成封面的临时访问地址。封面不可用时填 null，没有瞬间时填 0。
        return rows.stream().collect(Collectors.toMap(Album::getId, album -> {
            String key = album.getCoverFileId() == null ? null : keys.get(album.getCoverFileId());
            return new CircleSharedAlbumResponse(album.getId(), album.getName(), album.getDescription(),
                    key == null ? null : mediaService.createReadUrl(key), counts.getOrDefault(album.getId(), 0L));
        }));
    }

    /** 按分享者 ID 去重并批量查询昵称、头像等公开资料，返回以用户 ID 为键的 Map。 */
    private Map<UUID, PublicUserResponse> loadSharers(List<CircleShare> rows) {
        return userService.getPublicUsers(rows.stream().map(CircleShare::getSharerUserId).distinct().toList());
    }

    /** 判断当前用户是否是分享者或圈子创建者，供列表的 canWithdraw 和撤回操作使用。 */
    private boolean canWithdraw(CircleShare share, UUID creatorId, UUID userId) {
        return userId.equals(share.getSharerUserId()) || userId.equals(creatorId);
    }

    /** 生成分享不存在或不可访问时返回的 404 错误。 */
    private BusinessException shareUnavailable() {
        return BusinessException.notFound("SHARE_NOT_FOUND", "分享不存在或不可访问");
    }

    /** 把游标时间转成数据库查询用的 UTC 毫秒时间。年份超出 1000 到 9999 时返回 400。 */
    private LocalDateTime cursorTime(Instant value) {
        // 先检查时间范围，再将游标传给数据库查询。
        try {
            LocalDateTime time = LocalDateTime.ofInstant(value, ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
            if (time.getYear() >= 1000 && time.getYear() <= 9999) return time;
        } catch (DateTimeException exception) {
            throw BusinessException.invalidArgument("游标时间超出可查询范围");
        }
        throw BusinessException.invalidArgument("游标时间超出可查询范围");
    }

}
