package com.you.youbackend.circle.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.circle.response.*;
import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.mapper.*;
import com.you.youbackend.album.entity.AlbumMoment;
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
import com.you.youbackend.common.util.InviteCodeGenerator;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 圈子创建、访问控制、展示信息与分享引用维护；各写入入口明确事务边界。 */
@Log4j2
/* 处理圈子资料、成员访问和圈子分享。 */
@Service
public class CircleService {
    /** 单次 IN 查询的最大瞬间 ID 数。 */
    private static final int MOMENT_BATCH_SIZE = 500;
    /** 与数据库二进制 UUID 的正序一致。 */
    private static final Comparator<UUID> BINARY_UUID_ORDER = (left, right) -> {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    };
    private static final Comparator<Moment> SHARED_MOMENT_ORDER = Comparator.comparing(Moment::getMemoryTime)
            .thenComparing(Moment::getId, BINARY_UUID_ORDER);
    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final UserService userService;
    private final MediaService mediaService;
    private final CircleShareMapper circleShareMapper;
    private final MomentMapper momentMapper;
    private final AlbumMapper albumMapper;
    private final AlbumMomentMapper albumMomentMapper;
    private final MomentService momentService;
    private final String defaultAvatarKey; // TODO 不给默认值，直接null，默认值可以交给前端显示

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

    /** 创建圈子 ：【重复 ID 返回 409，不覆盖已有圈子】 */
    @Transactional
    public void createCircle(CreateCircleRequest request, UUID userId) {
        // 校验账号和自定义图片后，使用统一 UTC 毫秒时间创建圈子和成员关系。
        userService.getCurrentUser(userId);
        if (request.avatarFileId() != null){
            mediaService.validateOwnedImage(request.avatarFileId(), userId);
        }
        LocalDateTime now = utcNow();
        Circle circle = new Circle();
        circle.setId(request.id());
        circle.setCreatorUserId(userId);
        circle.setName(request.name());
        circle.setDescription(request.description());
        circle.setAvatarFileId(request.avatarFileId());
        circle.setInviteEnabled(true);
        circle.setInviteCode(InviteCodeGenerator.generate());
        circle.setCreatedAt(now);
        circle.setUpdatedAt(now);
        // 由数据库唯一约束检测重复提交，冲突时回滚创建事务。
        try {
            circleMapper.insert(circle);
            insertMembership(circle.getId(), userId, now);
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("RESOURCE_CONFLICT", "圈子或成员标识冲突，创建失败", exception);
        }
        log.info("已写入圈子及创建者成员关系，circleId={}，userId={}", circle.getId(), userId);
    }

    /** 查看圈子详情 ：【不存在、已解散和非成员统一返回 404】 */
    @Transactional(readOnly = true)
    public CircleDetailResponse getCircle(UUID circleId, UUID userId) {
        // 1. 确认访问资格，再读取全部有效成员关系。
        Circle circle = requireAccessibleCircle(circleId, userId);
        // 获取全部成员关系
        var memberships = circleMemberMapper.selectActiveMembers(circleId);
        // 2. 一次加载创建者和成员的公开资料，再组装响应。
        // 取出所有成员的 ID 集合
        Set<UUID> profileIds = memberships.stream().map(CircleMember::getUserId)
                .collect(Collectors.toSet());
        UUID creatorId = circle.getCreatorUserId();
        // 获取用户响应对象（id，name，avatar）列表
        var profiles = userService.getPublicUsers(profileIds);
        //
        var members = memberships.stream().map(member -> {
            UUID memberId = member.getUserId();
            // 把每一个 member 转化为 Member 对象，并封装为 list，得到 CircleDetailResponse 的 members 成员
            return new CircleDetailResponse.Member(requirePublicProfile(profiles, memberId),
                    creatorId.equals(memberId), member.getCreatedAt().toInstant(ZoneOffset.UTC));
        }).toList();
        // 构建 CircleDetailResponse 对象
        return CircleDetailResponse.builder()
                .id(circle.getId())
                .name(circle.getName())
                .description(circle.getDescription())
                .creator(requirePublicProfile(profiles, creatorId))
                .memberCount(members.size())
                .isCreator(creatorId.equals(userId))
                .inviteEnabled(circle.getInviteEnabled())
                .createdAt(circle.getCreatedAt().toInstant(ZoneOffset.UTC))
                .updatedAt(circle.getUpdatedAt().toInstant(ZoneOffset.UTC))
                .avatarFileId(circle.getAvatarFileId())
                .avatarUrl(loadCircleAvatar(circle))
                .members(members)
                .build();
    }

    /** 查看本人圈子列表 ：【按当前用户的成员关系倒序浏览圈子】 */
    @Transactional(readOnly = true)
    public ResultPage<CircleListResponse, CircleListCursor> listMyCircles(UUID userId, PageRequest<CircleListCursor> request) {
        // 多读一条判断后续页，游标始终使用成员关系的位置。
        CircleListCursor cursor = request.cursor();
        List<CircleListRow> rows = loadCircleListPage(userId, cursor, request.limit() + 1);
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

    /** 编辑圈子信息 ：【创建者全量保存名称、介绍和头像，省略头像表示移除自定义头像】 */
    @Transactional
    public void updateCircle(UUID circleId, UpdateCircleRequest request, UUID userId) {
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
    }

    /** 获取圈子邀请码 */
    @Transactional(readOnly = true)
    public CircleInvitationResponse getInvitation(UUID circleId, UUID userId) {
        // 先确认圈子是否存在以及用户是否在圈子里
        return invitationResponse(requireAccessibleCircle(circleId, userId));
    }

    /** 开启或关闭圈子邀请 */
    @Transactional
    public CircleInvitationResponse updateInvitation(UUID circleId, UpdateCircleInvitationRequest request, UUID userId) {
        // 1. 校验圈子访问资格和创建者身份。
        Circle circle = requireCircleCreator(circleId, userId, "只有圈子创建者可以管理邀请");
        // 2. 只在开关状态变化时写入，保留原邀请码。
        if (!request.inviteEnabled().equals(circle.getInviteEnabled())) {
            circle.setInviteEnabled(request.inviteEnabled());
            circle.setUpdatedAt(utcNow());
            circleMapper.updateInviteEnabled(circle);
            log.info("已写入圈子邀请开关，circleId={}，enabled={}", circleId, request.inviteEnabled());
        }
        return invitationResponse(circle);
    }

    /** 重置圈子邀请码 */
    @Transactional
    public CircleInvitationResponse resetInvitation(UUID circleId, UUID userId) {
        // 1. 校验圈子访问资格和创建者身份。
        Circle circle = requireCircleCreator(circleId, userId, "只有圈子创建者可以管理邀请");
        // 2. 生成新的随机邀请码并替换当前码，保留邀请开关。
        circle.setInviteCode(InviteCodeGenerator.generate());
        circle.setUpdatedAt(utcNow());
        try {
            if (circleMapper.updateInviteCode(circle) == 0) throw unavailable();
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("RESOURCE_CONFLICT", "邀请码生成冲突，请重新重置", exception);
        }
        log.info("已写入新圈子邀请码，circleId={}", circleId);
        return invitationResponse(circle);
    }

    /** 查看圈子加入预览 */
    @Transactional(readOnly = true)
    public CircleJoinPreviewResponse previewJoin(CircleJoinPreviewRequest request, UUID userId) {
        // 1.1 检查圈子是否解散、邀请是否关闭
        Circle circle = requireInvitingCircle(request.inviteCode());
        // 1.2 检查用户是否被圈子限制
        CircleMember membership = requireAllowedMembership(circle.getId(), userId);
        // 2. 加载可公开展示的资料，并根据有效成员关系返回成员状态。
        var summary = CircleJoinPreviewResponse.CircleSummary.builder()
                .id(circle.getId())
                .name(circle.getName())
                .description(circle.getDescription())
                .creator(loadCreator(circle))
                .avatarFileId(circle.getAvatarFileId())
                .avatarUrl(loadCircleAvatar(circle))
                .build();
        return new CircleJoinPreviewResponse(summary, membership != null && membership.getDeletedAt() == null);
    }

    /** 确认加入圈子 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void join(JoinCircleRequest request, UUID userId) {
        // 1.1 过滤圈子：检查圈子是否解散、邀请是否关闭
        Circle circle = requireInvitingCircle(request.inviteCode());
        UUID circleId = circle.getId();
        // 1.2 检查用户是否被圈子限制
        CircleMember membership = requireAllowedMembership(circleId, userId);
        // 2. 首次加入时新增，已离开时恢复，仍为成员时直接复用。
        if (membership == null) {
            insertJoiningMember(circleId, userId);
        } else if (membership.getDeletedAt() != null) {
            // 恢复原关系，保留原 ID、创建时间及分享状态。
            if (circleMemberMapper.restore(membership.getId(), utcNow()) == 1) {
                log.info("已恢复圈子成员关系，circleId={}，userId={}", circleId, userId);
            } else if (!circleMemberMapper.isActiveMember(circleId, userId)) {
                throw BusinessException.conflict("RESOURCE_CONFLICT", "圈子成员状态已变化，请重试");
            }
        }
    }

    /** 移除圈子成员 ：【同时撤回该成员的圈内分享并限制再次加入】 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void removeMember(UUID circleId, UUID targetId, UUID userId) {
        // 1. 检查圈子管理权限，创建者不能移除自己。
        requireCircleCreator(circleId, userId, "只有圈子创建者可以移除成员");
        if (userId.equals(targetId)) throw BusinessException.forbidden("圈子创建者不能移除自己");

        // 2. 仅变更有效成员关系；已被移除且仍受限时直接成功。
        LocalDateTime now = utcNow();
        if (circleMemberMapper.endMembership(circleId, targetId, true, now) == 0) {
            CircleMember history = circleMemberMapper.findIncludingDeleted(circleId, targetId);
            if (history != null && history.getDeletedAt() != null && Boolean.TRUE.equals(history.getJoinRestricted())) {
                return;
            }
            throw BusinessException.conflict("TARGET_UNAVAILABLE", "目标用户已不是可移除的圈子成员");
        }

        // 3. 在同一事务撤回该成员的圈内分享，原内容保留。
        circleShareMapper.invalidateMemberShares(circleId, targetId, now);
        log.info("已移除圈子成员并设置加入限制，circleId={}，userId={}", circleId, targetId);
    }

    /** 解除圈子加入限制 */
    @Transactional
    public void clearJoinRestriction(UUID circleId, UUID targetId, UUID userId) {
        // 只清除已有的加入限制，成员身份和分享状态保持原样。
        requireCircleCreator(circleId, userId, "只有圈子创建者可以解除加入限制");
        if (circleMemberMapper.clearRestriction(circleId, targetId, utcNow()) > 0) {
            log.info("已解除圈子加入限制，circleId={}，userId={}", circleId, targetId);
        }
    }

    /** 查看被限制加入的用户 */
    @Transactional(readOnly = true)
    public List<CircleJoinRestrictionResponse> listJoinRestrictions(UUID circleId, UUID userId) {
        // 1. 校验管理权限，再读取全部受限用户。
        requireCircleCreator(circleId, userId, "只有圈子创建者可以查看加入限制");
        var rows = circleMemberMapper.selectRestrictions(circleId);
        if (rows.isEmpty()) return List.of();

        // 2. 批量加载用户公开资料，沿用查询顺序组装结果。
        var profiles = userService.getPublicUsers(rows.stream().map(CircleMember::getUserId).toList());
        return rows.stream().map(row -> new CircleJoinRestrictionResponse(
                requirePublicProfile(profiles, row.getUserId()), row.getDeletedAt().toInstant(ZoneOffset.UTC)
        )).toList();
    }

    /** 退出圈子 ：【创建者不能直接退出】 */
    @Transactional
    public void leaveCircle(UUID circleId, UUID userId) {
        // 1. 确认调用者为有效普通成员。
        Circle circle = requireAccessibleCircle(circleId, userId);
        if (userId.equals(circle.getCreatorUserId())) {
            throw BusinessException.conflict("CREATOR_CANNOT_LEAVE", "圈子创建者不能直接退出，请解散圈子");
        }

        // 2. 结束成员关系并撤回本人圈内分享，主动退出不设置加入限制。
        LocalDateTime now = utcNow();
        if (circleMemberMapper.endMembership(circleId, userId, false, now) == 0) throw unavailable();
        circleShareMapper.invalidateMemberShares(circleId, userId, now);
        log.info("已退出圈子并撤回本人圈内分享，circleId={}，userId={}", circleId, userId);
    }

    /** 解散圈子 ：【同时使邀请、成员关系和圈内分享失效】 */
    @Transactional
    public void dissolveCircle(UUID circleId, UUID userId) {
        // 1. 创建者才能解散圈子，解散标记与邀请关闭同时写入。
        requireCircleCreator(circleId, userId, "只有圈子创建者可以解散圈子");
        LocalDateTime now = utcNow();
        if (circleMapper.dissolve(circleId, userId, now) == 0) throw unavailable();

        // 2. 在同一事务结束全部有效成员关系及圈内分享，原内容保留。
        circleMemberMapper.invalidateMemberships(circleId, now);
        circleShareMapper.invalidateCircleShares(circleId, now);
        log.info("已解散圈子，circleId={}，userId={}", circleId, userId);
    }

    /** 分享到多个圈子 */
    @Transactional
    public void shareToCircles(CreateCircleSharesRequest request, UUID userId) {
        // 1. 检查原内容是否存在，以及是否属于当前用户。
        requireOwnedObject(request, userId);
        // 2. 确认所有目标圈子都未解散，且当前用户仍是每个圈子的成员。
        if (circleMemberMapper.selectActiveCircleIds(userId, request.circleIds()).size() != request.circleIds().size()
                || circleMapper.selectByIds(request.circleIds()).size() != request.circleIds().size()) {
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

    /** 分页浏览圈子分享 ：【查下一页时传上一页最后一条分享的时间和 ID】 */
    @Transactional(readOnly = true)
    public ResultPage<CircleShareFeedResponse, CircleShareCursor> browseShares(
            UUID circleId,
            UUID userId,
            PageRequest<CircleShareCursor> request) {
        // 1. 检查当前用户的成员身份，再查仍可查看的分享。
        // 1.1 检查圈子是否存在，以及用户是否是圈子成员
        Circle circle = requireAccessibleCircle(circleId, userId);
        List<CircleShare> rows = loadSharePage(circleId, request.cursor(), request.limit() + 1);
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

    /** 分页浏览圈子共享时光中的瞬间 ：【查下一页时传上一页最后一个瞬间的发生时间和 ID】 */
    @Transactional(readOnly = true)
    public ResultPage<MomentResponse, CircleShareMomentCursor> browseSharedAlbumMoments(
            UUID circleId,
            UUID shareId,
            UUID userId,
            PageRequest<CircleShareMomentCursor> request) {
        // 1. 检查用户是否是成员、分享是否在当前圈子且仍有效，以及分享的内容是否为时光。
        requireAccessibleCircle(circleId, userId);
        CircleShare share = circleShareMapper.selectHistoryById(circleId, shareId);
        if (share == null || share.getWithdrawnAt() != null
                || !circleMemberMapper.isActiveMember(circleId, share.getSharerUserId())
                || !shareObjectExists(share)) throw shareUnavailable();
        if (share.getObjectType() != CircleShareObjectType.album) {
            throw BusinessException.conflict("SHARE_TYPE_MISMATCH", "该分享不是时光");
        }
        // 2. 查仍在该时光里且未被删除的瞬间，多查一条判断是否还有下一页。
        CircleShareMomentCursor cursor = request.cursor();
        List<Moment> rows = loadSharedMomentPage(share.getObjectId(), cursor, request.limit() + 1);
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

    /** 撤回圈子分享 */
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

    /** 按成员关系游标分批查找有效圈子，补足可见记录后再判断是否有下一页。 */
    private List<CircleListRow> loadCircleListPage(UUID userId, CircleListCursor cursor, int limit) {
        LocalDateTime time = cursor == null ? null : cursorTime(cursor.joinedAt());
        UUID id = cursor == null ? null : cursor.id();
        List<CircleListRow> rows = new ArrayList<>();
        while (rows.size() < limit) {
            // 先查有序成员关系，再批量补齐圈子并排除已解散记录。
            int batchSize = limit - rows.size();
            var candidates = circleMemberMapper.selectPageCandidates(userId, time, id, batchSize);
            if (candidates.isEmpty()) break;
            var circles = circleMapper.selectByIds(candidates.stream().map(CircleMember::getCircleId).toList())
                    .stream().collect(Collectors.toMap(Circle::getId, Function.identity()));
            for (CircleMember candidate : candidates) {
                Circle circle = circles.get(candidate.getCircleId());
                if (circle == null) continue;
                CircleListRow row = new CircleListRow();
                row.setId(circle.getId());
                row.setName(circle.getName());
                row.setAvatarFileId(circle.getAvatarFileId());
                row.setJoinedAt(candidate.getCreatedAt());
                row.setMembershipId(candidate.getId());
                rows.add(row);
            }
            if (candidates.size() < batchSize) break;
            CircleMember last = candidates.get(candidates.size() - 1);
            time = last.getCreatedAt();
            id = last.getId();
        }
        return rows;
    }

    /** 按分享游标分批筛选仍有效的分享者和原内容。 */
    private List<CircleShare> loadSharePage(UUID circleId, CircleShareCursor cursor, int limit) {
        LocalDateTime time = cursor == null ? null : cursorTime(cursor.sharedAt());
        UUID id = cursor == null ? null : cursor.id();
        List<CircleShare> rows = new ArrayList<>();
        int batchSize = Math.max(limit, 32);
        while (rows.size() < limit) {
            // 先按排序取候选，批量读取关联状态，再沿候选记录推进内部游标。
            var candidates = circleShareMapper.selectPageCandidates(circleId, time, id, batchSize);
            if (candidates.isEmpty()) break;
            var sharers = circleMemberMapper.selectActiveUserIds(circleId,
                    candidates.stream().map(CircleShare::getSharerUserId).distinct().toList());
            var momentIds = objectIds(candidates, CircleShareObjectType.moment);
            var albumIds = objectIds(candidates, CircleShareObjectType.album);
            Set<UUID> moments = momentIds.isEmpty() ? Set.of() : momentMapper.selectByIds(momentIds)
                    .stream().map(Moment::getId).collect(Collectors.toSet());
            Set<UUID> albums = albumIds.isEmpty() ? Set.of() : albumMapper.selectByIds(albumIds)
                    .stream().map(Album::getId).collect(Collectors.toSet());
            candidates.stream().filter(row -> sharers.contains(row.getSharerUserId()))
                    .filter(row -> (row.getObjectType() == CircleShareObjectType.moment ? moments : albums)
                            .contains(row.getObjectId()))
                    .limit(limit - rows.size()).forEach(rows::add);
            if (candidates.size() < batchSize) break;
            CircleShare last = candidates.get(candidates.size() - 1);
            time = last.getSharedAt();
            id = last.getId();
        }
        return rows;
    }

    /** 检查分享指向的原内容是否仍有效。 */
    private boolean shareObjectExists(CircleShare share) {
        // 按分享类型查询有效原内容，逻辑删除由 Mapper 过滤。
        return switch (share.getObjectType()) {
            case moment -> momentMapper.selectById(share.getObjectId()) != null;
            case album -> albumMapper.selectById(share.getObjectId()) != null;
            default -> false;
        };
    }

    /** 按瞬间发生时间和 ID 合并各批候选，最多返回一页加一条。 */
    private List<Moment> loadSharedMomentPage(UUID albumId, CircleShareMomentCursor cursor, int limit) {
        // 对每批关联应用相同的数据库游标，合并时只保留最靠前的记录。
        var ids = albumMomentMapper.selectActiveLinks(List.of(albumId)).stream()
                .map(AlbumMoment::getMomentId).toList();
        LocalDateTime time = cursor == null ? null : cursorTime(cursor.memoryTime());
        UUID id = cursor == null ? null : cursor.id();
        List<Moment> page = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += MOMENT_BATCH_SIZE) {
            var batch = ids.subList(start, Math.min(start + MOMENT_BATCH_SIZE, ids.size()));
            page.addAll(momentMapper.selectSharedPage(batch, time, id, limit));
            page.sort(SHARED_MOMENT_ORDER);
            if (page.size() > limit) page.subList(limit, page.size()).clear();
        }
        return page;
    }

    /** 统计各共享时光内仍有效且由其所有者发布的瞬间。 */
    private Map<UUID, Long> countSharedAlbumMoments(List<Album> albums) {
        if (albums.isEmpty()) return Map.of();
        // 批量补齐关联瞬间的作者，按时光所有者匹配后计数。
        var owners = albums.stream().collect(Collectors.toMap(Album::getId, Album::getOwnerUserId));
        var links = albumMomentMapper.selectActiveLinks(List.copyOf(owners.keySet()));
        var ids = links.stream().map(AlbumMoment::getMomentId).distinct().toList();
        Map<UUID, UUID> authors = new HashMap<>();
        for (int start = 0; start < ids.size(); start += MOMENT_BATCH_SIZE) {
            var batch = ids.subList(start, Math.min(start + MOMENT_BATCH_SIZE, ids.size()));
            for (Moment moment : momentMapper.selectActiveAuthors(batch)) {
                authors.put(moment.getId(), moment.getAuthorUserId());
            }
        }
        return links.stream().filter(link -> owners.get(link.getAlbumId()).equals(authors.get(link.getMomentId())))
                .collect(Collectors.groupingBy(AlbumMoment::getAlbumId, Collectors.counting()));
    }

    /** 校验圈子访问资格和创建者权限。 */
    private Circle requireCircleCreator(UUID circleId, UUID userId, String forbiddenMessage) {
        // 沿用圈子详情的访问规则，普通成员无权执行管理操作。
        Circle circle = requireAccessibleCircle(circleId, userId);
        if (!userId.equals(circle.getCreatorUserId())) {
            throw BusinessException.forbidden(forbiddenMessage);
        }
        return circle;
    }

    /** 过滤圈子：检查圈子是否解散、邀请是否关闭 */
    private Circle requireInvitingCircle(String inviteCode) {
        // 按长期邀请码查找圈子，已解散记录由 Mapper 过滤。
        Circle circle = circleMapper.findByInviteCode(inviteCode);
        if (circle == null) throw unavailable();
        if (!circle.getInviteEnabled()) {
            throw BusinessException.conflict("INVITATION_DISABLED", "圈子邀请已关闭");
        }
        return circle;
    }

    /** 检查用户是否被圈子限制 */
    private CircleMember requireAllowedMembership(UUID circleId, UUID userId) {
        // 已离开的成员也可能受到加入限制。
        CircleMember membership = circleMemberMapper.findIncludingDeleted(circleId, userId);
        if (membership != null && Boolean.TRUE.equals(membership.getJoinRestricted())) {
            throw BusinessException.forbidden("JOIN_RESTRICTED", "当前用户被限制加入该圈子");
        }
        return membership;
    }

    /** 返回邀请码：未启用邀请时返回 null */
    private CircleInvitationResponse invitationResponse(Circle circle) {
        // 保留响应中的邀请码字段，关闭时将其映射为 null。
        return new CircleInvitationResponse(circle.getInviteEnabled(),
                circle.getInviteEnabled() ? circle.getInviteCode() : null);
    }

    /** 读取创建者的公开资料。 */
    private PublicUserResponse loadCreator(Circle circle) {
        // 沿用圈子详情的资料读取和缺失处理规则。
        PublicUserResponse creator = userService.getPublicUsers(List.of(circle.getCreatorUserId()))
                .get(circle.getCreatorUserId());
        if (creator == null) throw new IllegalStateException("圈子创建者公开资料缺失：" + circle.getId());
        return creator;
    }

    /** 读取自定义头像地址，缺失或失效时返回默认头像。 */
    private String loadCircleAvatar(Circle circle) {
        // 优先使用自定义图片的读取地址，取不到时签发默认头像地址。
        UUID avatarId = circle.getAvatarFileId();
        if (avatarId != null) {
            String url = mediaService.getReadUrls(List.of(avatarId)).get(avatarId);
            if (url != null) return url;
        }
        return mediaService.createReadUrl(defaultAvatarKey);
    }

    /** 从已加载的资料中取用户，缺失时报告数据异常。 */
    private PublicUserResponse requirePublicProfile(Map<UUID, PublicUserResponse> profiles, UUID userId) {
        // 有效成员关系应关联到可读取的用户资料。
        var profile = profiles.get(userId);
        if (profile == null) throw new IllegalStateException("圈子用户公开资料缺失：" + userId);
        return profile;
    }

    /** 查看圈子详情；不存在、解散及非成员统一隐藏为 404。 */
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
        // 图片 过滤 null/id 去重，获取图片 id 列表
        var avatarIds = page.stream().map(CircleListRow::getAvatarFileId)
                .filter(Objects::nonNull).distinct().toList();
        var avatars = mediaService.getReadUrls(avatarIds);
        String defaultUrl = mediaService.createReadUrl(defaultAvatarKey);
        // 响应仅保留列表必需字段，未设置或失效的图片统一使用默认头像。
        return page.stream().map(circle -> new CircleListResponse(circle.getId(), circle.getName(),
                circle.getAvatarFileId() == null ? defaultUrl
                        : avatars.getOrDefault(circle.getAvatarFileId(), defaultUrl))).toList();
    }

    /** 首次加入时新增成员关系，允许并发重复加入成功。 */
    private void insertJoiningMember(UUID circleId, UUID userId) {
        try {
            insertMembership(circleId, userId, utcNow());
            log.info("已写入圈子成员关系，circleId={}，userId={}", circleId, userId);
        } catch (DuplicateKeyException conflict) {
            // 唯一键冲突后重新检查；已成为有效成员时按幂等成功处理。
            if (!circleMemberMapper.isActiveMember(circleId, userId)) {
                throw BusinessException.conflict("RESOURCE_CONFLICT", "圈子成员状态已变化，请重试", conflict);
            }
        }
    }

    /** 新建有效成员关系，调用方负责事务和状态校验。 */
    private void insertMembership(UUID circleId, UUID userId, LocalDateTime now) {
        // 为首次加入生成关系 ID，并使用同一 UTC 时间初始化审计字段。
        CircleMember member = new CircleMember();
        member.setId(UuidCreator.getTimeOrderedEpoch());
        member.setCircleId(circleId);
        member.setUserId(userId);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        circleMemberMapper.insert(member);
    }

    /** 取当前 UTC 时间，保留到毫秒，与数据库 DATETIME(3) 一致。 */
    private LocalDateTime utcNow() { //TODO 很多地方都用到了，可以考虑单独拿出来放到 common 包里
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

        return CircleShareFeedResponse.builder()
                .id(row.getId())
                .circleId(row.getCircleId())
                .objectType(row.getObjectType())
                .objectId(row.getObjectId())
                .sharer(sharer)
                .sharedAt(row.getSharedAt().toInstant(ZoneOffset.UTC))
                .updatedAt(row.getUpdatedAt().toInstant(ZoneOffset.UTC))
                .canWithdraw(canWithdraw(row, creatorId, userId))
                .moment(isMoment ? moment : null)
                .album(isAlbum ? album : null)
                .build();
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
        Map<UUID, Long> counts = countSharedAlbumMoments(rows);
        Map<UUID, String> keys = mediaService.findImageKeys(rows.stream().map(Album::getCoverFileId).filter(Objects::nonNull).distinct().toList());
        // 2. 生成封面的临时访问地址。封面不可用时填 null，没有瞬间时填 0。
        return rows.stream().collect(Collectors.toMap(Album::getId, album -> {
            String key = album.getCoverFileId() == null ? null : keys.get(album.getCoverFileId());
            return CircleSharedAlbumResponse.builder()
                    .id(album.getId())
                    .name(album.getName())
                    .description(album.getDescription())
                    .coverUrl(key == null ? null : mediaService.createReadUrl(key))
                    .momentCount(counts.getOrDefault(album.getId(), 0L))
                    .build();
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
