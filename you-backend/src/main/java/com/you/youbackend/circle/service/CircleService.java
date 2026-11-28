package com.you.youbackend.circle.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.entity.AlbumMoment;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.album.response.AlbumDetailedResponse;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.circle.entity.Circle;
import com.you.youbackend.circle.entity.CircleMember;
import com.you.youbackend.circle.entity.CircleShare;
import com.you.youbackend.circle.event.CircleContentsSharedEvent;
import com.you.youbackend.circle.event.CircleDissolvedEvent;
import com.you.youbackend.circle.event.CircleMemberLeftEvent;
import com.you.youbackend.circle.event.CircleShareWithdrawnEvent;
import com.you.youbackend.circle.mapper.CircleMapper;
import com.you.youbackend.circle.mapper.CircleMemberMapper;
import com.you.youbackend.circle.mapper.CircleShareMapper;
import com.you.youbackend.circle.model.CircleListCursor;
import com.you.youbackend.circle.model.CircleShareCursor;
import com.you.youbackend.circle.model.CircleShareMomentCursor;
import com.you.youbackend.circle.model.CircleSharedContent;
import com.you.youbackend.circle.request.CircleJoinPreviewRequest;
import com.you.youbackend.circle.request.CreateCircleRequest;
import com.you.youbackend.circle.request.CreateCircleSharesRequest;
import com.you.youbackend.circle.request.JoinCircleRequest;
import com.you.youbackend.circle.request.UpdateCircleInvitationRequest;
import com.you.youbackend.circle.request.UpdateCircleRequest;
import com.you.youbackend.circle.response.CircleDetailedResponse;
import com.you.youbackend.circle.response.CircleInvitationResponse;
import com.you.youbackend.circle.response.CircleJoinPreviewResponse;
import com.you.youbackend.circle.response.CircleJoinRestrictionResponse;
import com.you.youbackend.circle.response.CircleSummaryResponse;
import com.you.youbackend.circle.response.CircleSharedAlbumResponse;
import com.you.youbackend.circle.response.CircleShareFeedResponse;
import com.you.youbackend.common.enumeration.ObjectType;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.common.util.InviteCodeGenerator;
import com.you.youbackend.common.util.TimeUtils;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.response.MomentDetailedResponse;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 圈子创建与维护、成员关系与加入限制、圈内分享流及分享引用维护。
 * 资格校验统一返回不可访问语义（404），创建者管理操作返回 403。
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class CircleService {
    /** 单次 IN 查询的最大原内容 ID 数，防止超长 SQL。 */
    private static final int MOMENT_BATCH_SIZE = 500;
    /** 与数据库 BINARY(16) 的无符号字节序一致；UUID.compareTo 是有符号比较，合并各批候选时必须用它，否则翻页边界会乱。 */
    private static final Comparator<UUID> BINARY_UUID_ORDER = (left, right) -> {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    };
    /** 共享时光瞬间的合并排序：发生时间、ID 升序，与数据库游标排序一致。 */
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
    private final AlbumService albumService;
    private final ApplicationEventPublisher events;
    // TODO 不给默认值，直接null，默认值可以交给前端显示
    @Value("${storage.default-circle-avatar-key}")
    private String defaultAvatarKey;

    /**
     * 创建圈子和创建者成员关系，两类记录共同提交或回滚。
     *
     * <p>流程：校验账号与头像可用性 → 组装圈子主记录入库 → 把创建者写成第一个有效成员。
     * 副作用：circle、circle_member 同事务写入；ID 或邀请码撞唯一索引整批回滚并报 409，不覆盖已有圈子。</p>
     *
     * @param request 客户端预生成 UUIDv7 的创建请求，名称已去首尾空白
     * @param userId  当前登录用户 ID
     * @throws BusinessException 账号不存在返回 404、头像不可用返回 400，ID 或邀请码冲突返回 409
     */
    @Transactional
    public void createCircle(CreateCircleRequest request, UUID userId) {
        // 1. 校验头像图片可用且属于本人；null 表示不设置，validateOwnedImage 直接放行。
        mediaService.validateOwnedImage(request.avatarFileId(), userId);
        Instant now = TimeUtils.now();
        // 2. 组装圈子主记录入库：UUIDv7 由客户端预生成，邀请码服务端生成，初始邀请开启。
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
        // 3. 把创建者写成第一个有效成员；由数据库唯一约束检测重复提交，冲突时回滚整个创建事务。
        try {
            circleMapper.insert(circle);
            insertMembership(circle.getId(), userId, now);
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("RESOURCE_CONFLICT", "圈子或成员标识冲突，创建失败", exception);
        }
        log.info("已写入圈子及创建者成员关系，circleId={}，userId={}", circle.getId(), userId);
    }

    /**
     * 读取圈子详情：创建者公开资料和按加入时间排列的全部有效成员。
     *
     * <p>流程：校验访问资格 → 读取全部有效成员关系 → 批量加载成员公开资料 → 组装详情。
     * 只读查询，无副作用。成员或创建者资料缺失时按数据异常大声失败。</p>
     *
     * @param circleId 圈子 ID
     * @param userId   当前认证用户 ID
     * @return 圈子详情，含成员列表和当前用户的创建者标记
     * @throws BusinessException 圈子不存在、已解散或非成员统一返回 404，不暴露圈子是否存在
     */
    @Transactional(readOnly = true)
    public CircleDetailedResponse getCircleInfo(UUID circleId, UUID userId) {
        // 1. 校验访问资格：不存在、已解散和非成员统一 404。
        Circle circle = getAccessibleCircle(circleId, userId);
        // 2. 读取全部有效成员关系，按加入时间正序返回。
        List<CircleMember> memberships = circleMemberMapper.selectActiveMembers(circleId);
        // 3. 一次批量加载创建者和成员的公开资料，再按成员顺序组装响应。
        Set<UUID> profileIds = memberships.stream().map(CircleMember::getUserId)
                .collect(Collectors.toSet());
        UUID creatorId = circle.getCreatorUserId();
        Map<UUID, PublicUserResponse> profiles = userService.getPublicUsers(profileIds);
        List<CircleDetailedResponse.Member> members = memberships.stream().map(member -> {
            UUID memberId = member.getUserId();
            return new CircleDetailedResponse.Member(getPublicProfile(profiles, memberId),
                    creatorId.equals(memberId), member.getCreatedAt());
        }).toList();
        return convertToDetailedResponse(circle, getPublicProfile(profiles, creatorId), members,
                creatorId.equals(userId));
    }

    /**
     * 按加入时间倒序分页浏览本人的圈子列表，只补齐头像。
     *
     * <p>流程：按游标查本人成员关系（多取一条判断下一页）→ 批量补齐圈子主记录和头像 → 组装摘要页。
     * 只读查询，无副作用。创建者资料和成员数由详情接口加载，这里不查。</p>
     *
     * @param userId  当前认证用户 ID
     * @param request 已校验的加入时间、圈子 ID 游标分页参数
     * @return 摘要页；游标为上一页末项的加入时间和圈子 ID，头像未设置或失效时回落默认头像
     */
    @Transactional(readOnly = true)
    public ResultPage<CircleSummaryResponse, CircleListCursor> browseMyCircles(UUID userId, PageRequest<CircleListCursor> request) {
        // 1. 按游标查本人有效成员关系，按加入时间、圈子 ID 倒序，多取一条判断下一页。
        CircleListCursor cursor = request.cursor();
        Instant cursorTime = cursor == null ? null : cursor.joinedAt();
        UUID cursorId = cursor == null ? null : cursor.id();
        List<CircleMember> rows = circleMemberMapper.selectPageAfterCursorByUserId(userId, cursorTime, cursorId, request.limit() + 1);
        boolean hasMore = rows.size() > request.limit();
        List<CircleMember> page = hasMore ? rows.subList(0, request.limit()) : rows;
        CircleListCursor next = null;
        if (hasMore) {
            CircleMember last = page.get(page.size() - 1);
            next = new CircleListCursor(last.getCreatedAt(), last.getCircleId());
        }
        // 2. 批量补齐圈子主记录和头像，组装摘要页，保持加入时间倒序。
        return ResultPage.toResultPage(convertToSummaryResponses(page), hasMore, next);
    }

    /**
     * 创建者全量保存圈子名称、介绍和头像。
     *
     * <p>流程：校验访问资格与创建者身份 → 校验头像可用性 → 覆盖可编辑字段写库。
     * 副作用：circle 单表更新；介绍和头像传 null 显式清空，展示时头像回落默认头像。</p>
     *
     * @param circleId 圈子 ID
     * @param request  已校验的名称、介绍和头像，名称已去首尾空白
     * @param userId   当前认证用户 ID
     * @throws BusinessException 圈子不存在或不可访问返回 404，非创建者返回 403，头像不可用返回 400
     */
    @Transactional
    public void updateCircle(UUID circleId, UpdateCircleRequest request, UUID userId) {
        // 1. 校验访问资格和创建者身份；不存在、已解散和非成员统一 404，普通成员 403。
        Circle circle = getCreatorCircle(circleId, userId);
        // 2. 校验头像图片可用且属于本人；null 表示清空自定义头像，validateOwnedImage 直接放行。
        mediaService.validateOwnedImage(request.avatarFileId(), userId);
        // 3. 覆盖名称、介绍、头像并刷新修改时间写库。
        circle.setName(request.name());
        circle.setDescription(request.description());
        circle.setAvatarFileId(request.avatarFileId());
        circle.setUpdatedAt(TimeUtils.now());
        if (circleMapper.updateContentById(circle) == 0) throw unavailable();
        log.info("已写入圈子展示信息，circleId={}，userId={}", circleId, userId);
    }

    /**
     * 读取圈子邀请状态；邀请关闭时邀请码按约定映射为 null。
     *
     * <p>流程：校验访问资格 → 读取邀请开关和邀请码组装响应。
     * 只读查询，无副作用。</p>
     *
     * @param circleId 圈子 ID
     * @param userId   当前认证用户 ID
     * @return 邀请开关和当前可用邀请码
     * @throws BusinessException 圈子不存在、已解散或非成员统一返回 404
     */
    @Transactional(readOnly = true)
    public CircleInvitationResponse getInvitation(UUID circleId, UUID userId) {
        // 校验访问资格后读取邀请状态。
        return convertToInvitationResponse(getAccessibleCircle(circleId, userId));
    }

    /**
     * 创建者开启或关闭圈子邀请，邀请码保持不变。
     *
     * <p>流程：校验创建者身份 → 仅在开关状态变化时写库。
     * 副作用：circle 单表更新；状态未变化时不写库不刷修改时间。</p>
     *
     * @param circleId 圈子 ID
     * @param request  目标开关状态，false 表示关闭
     * @param userId   当前认证用户 ID
     * @return 最新邀请状态
     * @throws BusinessException 圈子不存在或不可访问返回 404，非创建者返回 403
     */
    @Transactional
    public CircleInvitationResponse updateInvitation(UUID circleId, UpdateCircleInvitationRequest request, UUID userId) {
        // 1. 校验访问资格和创建者身份。
        Circle circle = getCreatorCircle(circleId, userId);
        // 2. 只在开关状态变化时写入，保留原邀请码。
        if (!request.inviteEnabled().equals(circle.getInviteEnabled())) {
            circle.setInviteEnabled(request.inviteEnabled());
            circle.setUpdatedAt(TimeUtils.now());
            circleMapper.updateInviteEnabled(circle);
            log.info("已写入圈子邀请开关，circleId={}，enabled={}", circleId, request.inviteEnabled());
        }
        return convertToInvitationResponse(circle);
    }

    /**
     * 创建者重置圈子邀请码，邀请开关保持不变；旧邀请码立即失效。
     *
     * <p>流程：校验创建者身份 → 生成新邀请码写库。
     * 副作用：circle 单表更新；邀请码撞唯一索引时抛 409，原码保留。</p>
     *
     * @param circleId 圈子 ID
     * @param userId   当前认证用户 ID
     * @return 含新邀请码的最新邀请状态
     * @throws BusinessException 圈子不存在或不可访问返回 404，非创建者返回 403，邀请码冲突返回 409
     */
    @Transactional
    public CircleInvitationResponse resetInvitation(UUID circleId, UUID userId) {
        // 1. 校验访问资格和创建者身份。
        Circle circle = getCreatorCircle(circleId, userId);
        // 2. 生成新的随机邀请码并替换当前码，保留邀请开关。
        circle.setInviteCode(InviteCodeGenerator.generate());
        circle.setUpdatedAt(TimeUtils.now());
        try {
            if (circleMapper.updateInviteCode(circle) == 0) throw unavailable();
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("RESOURCE_CONFLICT", "邀请码生成冲突，请重新重置", exception);
        }
        log.info("已写入新圈子邀请码，circleId={}", circleId);
        return convertToInvitationResponse(circle);
    }

    /**
     * 按邀请码预览加入目标，返回可展示资料和当前成员标记，整个流程只读取数据。
     *
     * <p>流程：按邀请码定位可加入圈子 → 校验当前用户未被限制加入 → 加载创建者资料和头像组装预览。
     * 只读查询，无副作用。</p>
     *
     * @param request 已通过邀请码校验的查询参数
     * @param userId  当前登录用户 ID
     * @return 圈子基本资料和当前用户是否已是有效成员
     * @throws BusinessException 邀请码无效或圈子已解散返回 404，邀请关闭返回 409，被限制加入返回 403
     */
    @Transactional(readOnly = true)
    public CircleJoinPreviewResponse previewJoin(CircleJoinPreviewRequest request, UUID userId) {
        // 1. 按邀请码定位可加入圈子：未解散且邀请开关还开着。
        Circle circle = getJoinableCircle(request.inviteCode());
        // 2. 校验当前用户未被限制加入；返回的关系含已退出历史，供下面判断成员标记。
        CircleMember membership = getJoinableMembership(circle.getId(), userId);
        // 3. 加载创建者资料组装预览；已是有效成员时标记 true，前端可直接进入。
        PublicUserResponse creator = getPublicProfile(userService.getPublicUsers(List.of(circle.getCreatorUserId())),
                circle.getCreatorUserId());
        return new CircleJoinPreviewResponse(
                convertToJoinSummary(circle, creator, loadCircleAvatar(circle)),
                membership != null && membership.getDeletedAt() == null);
    }

    /**
     * 确认加入圈子：首次新增、已离开恢复、已是成员直接成功。
     *
     * <p>流程：按邀请码定位可加入圈子 → 校验当前用户未被限制加入 → 按成员状态新增或恢复关系。
     * 副作用：circle_member 同事务写入；并发首次加入撞唯一索引时复查，已是有效成员按成功处理。</p>
     *
     * @param request 已通过邀请码校验的请求
     * @param userId  当前登录用户 ID
     * @throws BusinessException 邀请码无效或圈子已解散返回 404，邀请关闭返回 409，被限制加入返回 403，
     *                          并发下成员状态异常返回 409
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void joinCircle(JoinCircleRequest request, UUID userId) {
        // 1. 按邀请码定位可加入圈子：未解散且邀请开关还开着。
        Circle circle = getJoinableCircle(request.inviteCode());
        UUID circleId = circle.getId();
        // 2. 校验当前用户未被限制加入；返回的关系含已退出历史，供下面决定新增还是恢复。
        CircleMember membership = getJoinableMembership(circleId, userId);
        // 3. 首次加入时新增，已离开时恢复原关系（保留原 ID、创建时间及分享状态），仍为成员时不动。
        if (membership == null) {
            insertJoiningMember(circleId, userId);
        } else if (membership.getDeletedAt() != null) {
            if (circleMemberMapper.restore(membership.getId(), TimeUtils.now()) == 1) {
                log.info("已恢复圈子成员关系，circleId={}，userId={}", circleId, userId);
            } else if (!circleMemberMapper.existsByCircleIdAndUserId(circleId, userId)) {
                // 恢复失败且当前仍非成员，说明并发下关系状态已变化。
                throw BusinessException.conflict("RESOURCE_CONFLICT", "圈子成员状态已变化，请重试");
            }
        }
    }

    /**
     * 创建者移除圈子成员：结束成员关系、撤回该成员的圈内分享并限制再次加入。
     *
     * <p>流程：校验创建者身份（不能移除自己）→ 结束目标成员关系并写入加入限制 → 撤回其全部圈内分享 → 发布移除事件。
     * 副作用：circle_member、circle_share 同事务写入；目标已被移除且仍受限时按幂等成功返回，原内容保留。</p>
     *
     * @param circleId 圈子 ID
     * @param targetId 被移除的成员用户 ID
     * @param userId   当前认证用户 ID，须为创建者
     * @throws BusinessException 圈子不存在或不可访问返回 404，非创建者或移除自己返回 403，
     *                          目标已不是可移除成员返回 409
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void removeMember(UUID circleId, UUID targetId, UUID userId) {
        // 1. 校验访问资格和创建者身份；创建者不能移除自己。
        getCreatorCircle(circleId, userId);
        if (userId.equals(targetId)) throw BusinessException.forbidden("圈子创建者不能移除自己");

        // 2. 结束有效成员关系并写入加入限制；已被移除且仍受限时直接成功（幂等）。
        Instant now = TimeUtils.now();
        if (circleMemberMapper.endMembership(circleId, targetId, true, now) == 0) {
            CircleMember history = circleMemberMapper.selectIncludingDeletedByCircleIdAndUserId(circleId, targetId);
            if (history != null && history.getDeletedAt() != null && Boolean.TRUE.equals(history.getJoinRestricted())) {
                return;
            }
            throw BusinessException.conflict("TARGET_UNAVAILABLE", "目标用户已不是可移除的圈子成员");
        }

        // 3. 在同一事务撤回该成员的圈内分享，原内容保留。
        circleShareMapper.withdrawMemberShares(circleId, targetId, now);

        // 4. 发布移除事件，收件箱等下游各自失效。
        events.publishEvent(new CircleMemberLeftEvent(circleId, targetId, now));

        log.info("已移除圈子成员并设置加入限制，circleId={}，userId={}", circleId, targetId);
    }

    /**
     * 创建者解除指定用户的加入限制，成员身份和分享状态保持原样。
     *
     * <p>流程：校验创建者身份 → 清除目标用户的加入限制（无限制时不做修改）。
     * 副作用：circle_member 单表更新。</p>
     *
     * @param circleId 圈子 ID
     * @param targetId 被解除限制的用户 ID
     * @param userId   当前认证用户 ID，须为创建者
     * @throws BusinessException 圈子不存在或不可访问返回 404，非创建者返回 403
     */
    @Transactional
    public void clearJoinRestriction(UUID circleId, UUID targetId, UUID userId) {
        // 校验创建者身份后清除已有的加入限制。
        getCreatorCircle(circleId, userId);
        if (circleMemberMapper.clearRestriction(circleId, targetId, TimeUtils.now()) > 0) {
            log.info("已解除圈子加入限制，circleId={}，userId={}", circleId, targetId);
        }
    }

    /**
     * 创建者查看仍被限制再次加入的用户，按移除时间倒序。
     *
     * <p>流程：校验创建者身份 → 读取全部受限成员 → 批量加载用户公开资料组装结果。
     * 只读查询，无副作用。</p>
     *
     * @param circleId 圈子 ID
     * @param userId   当前认证用户 ID，须为创建者
     * @return 受限用户资料及限制时间列表，无受限用户时为空
     * @throws BusinessException 圈子不存在或不可访问返回 404，非创建者返回 403
     */
    @Transactional(readOnly = true)
    public List<CircleJoinRestrictionResponse> listJoinRestrictions(UUID circleId, UUID userId) {
        // 1. 校验创建者身份，再读取全部受限成员。
        getCreatorCircle(circleId, userId);
        List<CircleMember> rows = circleMemberMapper.selectRestrictedMembers(circleId);
        if (rows.isEmpty()) return List.of();

        // 2. 批量加载用户公开资料，沿用查询顺序组装结果。
        Map<UUID, PublicUserResponse> profiles = userService.getPublicUsers(rows.stream().map(CircleMember::getUserId).toList());
        return rows.stream().map(row -> new CircleJoinRestrictionResponse(
                getPublicProfile(profiles, row.getUserId()), row.getDeletedAt()
        )).toList();
    }

    /**
     * 普通成员主动退出圈子并撤回本人全部圈内分享，主动退出不设置加入限制。
     *
     * <p>流程：校验访问资格（创建者须改用解散）→ 结束成员关系 → 撤回本人圈内分享 → 发布退出事件。
     * 副作用：circle_member、circle_share 同事务写入，原内容保留。</p>
     *
     * @param circleId 圈子 ID
     * @param userId   当前认证用户 ID
     * @throws BusinessException 圈子不存在、已解散或非成员统一返回 404，创建者退出返回 409
     */
    @Transactional
    public void leaveCircle(UUID circleId, UUID userId) {
        // 1. 校验访问资格；创建者不能直接退出，必须走解散。
        Circle circle = getAccessibleCircle(circleId, userId);
        if (userId.equals(circle.getCreatorUserId())) {
            throw BusinessException.conflict("CREATOR_CANNOT_LEAVE", "圈子创建者不能直接退出，请解散圈子");
        }

        // 2. 结束成员关系并撤回本人圈内分享。
        Instant now = TimeUtils.now();
        if (circleMemberMapper.endMembership(circleId, userId, false, now) == 0) throw unavailable();
        circleShareMapper.withdrawMemberShares(circleId, userId, now);

        // 3. 发布退出事件，收件箱等下游各自失效。
        events.publishEvent(new CircleMemberLeftEvent(circleId, userId, now));

        log.info("已退出圈子并撤回本人圈内分享，circleId={}，userId={}", circleId, userId);
    }

    /**
     * 创建者解散圈子：邀请关闭、成员关系和圈内分享全部失效，原内容保留。
     *
     * <p>流程：校验创建者身份 → 软删圈子并关闭邀请 → 结束全部成员关系 → 撤回全部圈内分享 → 发布解散事件。
     * 副作用：circle、circle_member、circle_share 同事务写入，失败整体回滚。</p>
     *
     * @param circleId 圈子 ID
     * @param userId   当前认证用户 ID，须为创建者
     * @throws BusinessException 圈子不存在或不可访问返回 404，非创建者返回 403
     */
    @Transactional
    public void dissolveCircle(UUID circleId, UUID userId) {
        // 1. 校验创建者身份；解散标记与邀请关闭同时写入。
        getCreatorCircle(circleId, userId);
        Instant now = TimeUtils.now();
        if (circleMapper.deleteByCircleIdAndCreatorUserId(circleId, userId, now) == 0) throw unavailable();

        // 2. 在同一事务结束全部有效成员关系及圈内分享，原内容保留。
        circleMemberMapper.endAllMemberships(circleId, now);
        circleShareMapper.withdrawCircleShares(circleId, now);

        // 3. 发布解散事件，收件箱等下游各自清理。
        events.publishEvent(new CircleDissolvedEvent(
                circleId,
                now
        ));

        log.info("已解散圈子，circleId={}，userId={}", circleId, userId);
    }

    /**
     * 把本人的一条瞬间或时光分享到多个圈子；从未分享的新增、已撤回的恢复、仍有效的保持原样。
     *
     * <p>流程：校验原内容属于本人 → 校验每个目标圈子可访问且本人仍是成员 → 查各圈子的分享历史分新增/恢复/跳过
     * → 批量写入新增与恢复 → 为实际变更的分享发布事件。
     * 副作用：circle_share 同事务写入；唯一键冲突或恢复条数不足整批回滚并报 409。原内容不动。</p>
     *
     * @param request 原内容类型、ID 和目标圈子列表（至少一个且不重复）
     * @param userId  当前登录用户 ID
     * @throws BusinessException 原内容不存在返回 404、非本人返回 403；目标圈子不存在、已解散或非成员返回 404；
     *                          并发分享冲突返回 409
     */
    @Transactional
    public void shareToCircles(CreateCircleSharesRequest request, UUID userId) {
        // 1. 校验原内容存在且属于本人：瞬间看作者，时光看所有者。
        validateOwnedObject(request, userId);
        // 2. 确认当前用户仍是每个目标圈子的有效成员；解散会同步结束成员关系，成员校验即圈子有效性校验。
        if (circleMemberMapper.selectActiveCircleIds(userId, request.circleIds()).size() != request.circleIds().size()) {
            throw BusinessException.notFound("CIRCLE_NOT_FOUND", "目标圈子不存在或不可访问");
        }

        // 3. 查这条内容在目标圈子里的分享历史（含已撤回），按圈子 ID 索引后决定每个圈子的处置。
        Map<UUID, CircleShare> history = circleShareMapper.selectIncludingDeletedByObjectId(request.objectType(), request.objectId(), request.circleIds())
                .stream().collect(Collectors.toMap(CircleShare::getCircleId, Function.identity()));
        Instant now = TimeUtils.now();
        List<CircleShare> additions = new ArrayList<>();
        List<UUID> restores = new ArrayList<>();
        List<CircleSharedContent> sharedContents = new ArrayList<>();

        // 4. 从未分享过就新增，已撤回就恢复，仍有效就跳过（无变更不投递事件）。
        for (UUID circleId : request.circleIds()) {
            CircleShare row = history.get(circleId);
            if (row == null) {
                row = newShare(request, circleId, userId, now);
                additions.add(row);
            } else if (row.getDeletedAt() != null) {
                restores.add(row.getId());
            }else{
                continue;
            }
            sharedContents.add(new CircleSharedContent(
                    circleId, row.getId(), request.objectType(), request.objectId()
            ));
        }
        // 5. 在同一事务里写入新增和恢复的记录，状态并发变化时整批回滚。
        persistChanges(additions, restores, userId, now);

        // 6. 为实际变更的分享发布事件，收件箱等下游各自写入。
        if (!sharedContents.isEmpty()) {
            events.publishEvent(new CircleContentsSharedEvent(userId, sharedContents, now));
        }
        log.info("已写入圈子分享，userId={}，targetCount={}，addedCount={}，restoredCount={}",
                userId, request.circleIds().size(), additions.size(), restores.size());
    }

    /**
     * 按分享时间倒序分页浏览圈内分享流，条目含原内容卡片和分享者资料。
     *
     * <p>流程：校验访问资格 → 按游标分批筛出仍可查看的分享（分享者仍是成员、原内容仍有效）
     * → 为本页加载瞬间详情或时光卡片及分享者资料 → 组装条目。
     * 只读查询，无副作用。下一页游标为上一页末条分享的时间和分享 ID。</p>
     *
     * @param circleId 圈子 ID
     * @param userId   当前认证用户 ID
     * @param request  已校验的分享时间、分享 ID 游标分页参数
     * @return 分享流页；每条只填 moment 或 album 其中一个字段
     * @throws BusinessException 圈子不存在、已解散或非成员统一返回 404
     */
    @Transactional(readOnly = true)
    public ResultPage<CircleShareFeedResponse, CircleShareCursor> browseShares(
            UUID circleId,
            UUID userId,
            PageRequest<CircleShareCursor> request) {
        // 1. 校验访问资格，再按游标多取一条判断后续页；候选里的失效记录由 loadSharePage 批量过滤。
        Circle circle = getAccessibleCircle(circleId, userId);
        List<CircleShare> rows = loadSharePage(circleId, request.cursor(), request.limit() + 1);
        boolean hasMore = rows.size() > request.limit();
        List<CircleShare> page = hasMore ? rows.subList(0, request.limit()) : rows;
        // 2. 只为本页记录加载原内容和分享者资料，保持查询顺序组装。
        List<CircleShareFeedResponse> items = loadFeed(page, circle.getCreatorUserId(), userId);
        CircleShareCursor next = null;
        if (hasMore) {
            CircleShare last = page.get(page.size() - 1);
            next = new CircleShareCursor(last.getSharedAt(), last.getId());
        }
        return new ResultPage<>(items, next, hasMore);
    }

    /**
     * 沿圈子分享读取完整瞬间；只读事务内先校验来源，再加载媒体与公开资料。
     *
     * <p>流程：校验圈子访问资格和分享有效（分享者仍是成员、类型为瞬间）→ 读取分享的原瞬间并复核作者仍是分享者
     * → 补齐图片、标签和作者资料。
     * 只读查询，无副作用。</p>
     *
     * @param circleId 来源圈子 ID
     * @param shareId  该圈子的分享关系 ID
     * @param userId   当前认证用户 ID
     * @return 原瞬间完整详情，作者资料缺失时为 null
     * @throws BusinessException 圈子、分享或原瞬间不可访问统一返回 404，分享类型不匹配返回 409
     */
    @Transactional(readOnly = true)
    public MomentDetailedResponse getSharedMoment(UUID circleId, UUID shareId, UUID userId) {
        // 1. 校验圈子访问资格、分享有效性和类型，再读取分享的原瞬间。
        CircleShare share = getAccessibleShare(circleId, shareId, userId, ObjectType.moment);
        Moment moment = getSourceMoment(share);
        // 2. 使用已授权实体补齐图片、标签和作者，避免重复查询瞬间。
        log.debug("查询圈子共享瞬间，circleId={}，shareId={}，momentId={}，userId={}",
                circleId, shareId, moment.getId(), userId);
        return momentService.convertToDetailedResponse(moment);
    }

    /**
     * 沿圈子分享读取时光信息，复用时光模块的统计、封面读取和响应组装。
     *
     * <p>流程：校验圈子访问资格和分享有效（分享者仍是成员、类型为时光）→ 读取分享的原时光并复核所有者仍是分享者
     * → 以时光所有者身份统计瞬间、读取封面组装详情。
     * 只读查询，无副作用。</p>
     *
     * @param circleId 来源圈子 ID
     * @param shareId  该圈子的分享关系 ID
     * @param userId   当前认证用户 ID
     * @return 当前有效时光的完整信息，空时光的瞬间数量为零
     * @throws BusinessException 圈子、分享或原时光不可访问统一返回 404，分享类型不匹配返回 409
     */
    @Transactional(readOnly = true)
    public AlbumDetailedResponse getSharedAlbum(UUID circleId, UUID shareId, UUID userId) {
        // 1. 校验圈子访问资格、分享有效性和类型，再读取分享的原时光。
        CircleShare share = getAccessibleShare(circleId, shareId, userId, ObjectType.album);
        Album album = getSourceAlbum(share);
        // 2. 以时光所有者统计瞬间、读取封面；查看者身份只用于来源授权。
        log.debug("查询圈子共享时光，circleId={}，shareId={}，albumId={}，userId={}",
                circleId, shareId, album.getId(), userId);
        return albumService.convertToDetailedResponse(album);
    }

    /**
     * 沿圈子共享时光读取一个瞬间；关联、原内容和作者范围都通过后才加载详情。
     *
     * <p>流程：校验圈子访问资格和时光分享有效 → 读取原时光并复核所有者 → 确认瞬间仍在时光里且由所有者发布
     * → 补齐图片、标签和作者资料。
     * 只读查询，无副作用。</p>
     *
     * @param circleId 来源圈子 ID
     * @param shareId  目标时光的分享关系 ID
     * @param momentId 时光内部瞬间 ID
     * @param userId   当前认证用户 ID
     * @return 该时光中由所有者发布的有效瞬间详情
     * @throws BusinessException 圈子、分享、时光或内部瞬间不可访问统一返回 404，分享类型不匹配返回 409
     */
    @Transactional(readOnly = true)
    public MomentDetailedResponse getSharedAlbumMoment(UUID circleId, UUID shareId, UUID momentId, UUID userId) {
        // 1. 校验圈子访问资格和时光分享有效，确认当前用户能访问分享的时光。
        CircleShare share = getAccessibleShare(circleId, shareId, userId, ObjectType.album);
        Album album = getSourceAlbum(share);
        // 2. 确认瞬间仍在时光里且由时光所有者发布，随后补齐详情资源。
        Moment moment = getSourceAlbumMoment(album, momentId);
        log.debug("查询圈子共享时光内瞬间，circleId={}，shareId={}，momentId={}，userId={}",
                circleId, shareId, momentId, userId);
        return momentService.convertToDetailedResponse(moment);
    }

    /**
     * 分页浏览圈子共享时光中的瞬间，按发生时间、瞬间 ID 正序。
     *
     * <p>流程：校验圈子访问资格和时光分享有效 → 按游标分批筛出时光内仍有效的瞬间 → 为本页补齐图片、标签和作者。
     * 只读查询，无副作用。下一页游标为上一页末个瞬间的发生时间和瞬间 ID。</p>
     *
     * @param circleId 来源圈子 ID
     * @param shareId  目标时光的分享关系 ID
     * @param userId   当前认证用户 ID
     * @param request  已校验的发生时间、瞬间 ID 游标分页参数
     * @return 时光内瞬间详情页
     * @throws BusinessException 圈子、分享或原时光不可访问统一返回 404，分享类型不匹配返回 409
     */
    @Transactional(readOnly = true)
    public ResultPage<MomentDetailedResponse, CircleShareMomentCursor> browseSharedAlbumMoments(
            UUID circleId,
            UUID shareId,
            UUID userId,
            PageRequest<CircleShareMomentCursor> request) {
        // 1. 校验圈子访问资格和分享有效（分享者仍是成员、原时光仍存在），并确认分享的是时光。
        getAccessibleCircle(circleId, userId);
        CircleShare share = circleShareMapper.selectIncludingDeleted(circleId, shareId);
        if (share == null || share.getWithdrawnAt() != null
                || !circleMemberMapper.existsByCircleIdAndUserId(circleId, share.getSharerUserId())
                || !shareObjectExists(share)) throw shareUnavailable();
        if (share.getObjectType() != ObjectType.album) {
            throw BusinessException.conflict("SHARE_TYPE_MISMATCH", "该分享不是时光");
        }
        // 2. 按游标查仍在该时光里且未被删除的瞬间，多取一条判断下一页。
        CircleShareMomentCursor cursor = request.cursor();
        List<Moment> rows = loadSharedMomentPage(share.getObjectId(), cursor, request.limit() + 1);
        boolean hasMore = rows.size() > request.limit();
        List<Moment> page = hasMore ? rows.subList(0, request.limit()) : rows;
        CircleShareMomentCursor next = null;
        if (hasMore) {
            Moment last = page.get(page.size() - 1);
            next = new CircleShareMomentCursor(last.getMemoryTime(), last.getId());
        }
        // 3. 为本页瞬间补齐图片、标签和作者资料，保持发生时间正序。
        return new ResultPage<>(momentService.loadAuthorizedResponses(page), next, hasMore);
    }

    /**
     * 撤回圈内的一条分享：分享者本人或圈子创建者可操作，重复撤回直接成功。
     *
     * <p>流程：校验访问资格 → 读取分享历史并校验撤回权限 → 写入撤回时间（已撤回的原样返回）→ 发布撤回事件。
     * 副作用：circle_share 单表更新，原内容不动。</p>
     *
     * @param circleId 圈子 ID
     * @param shareId  分享关系 ID
     * @param userId   当前认证用户 ID
     * @throws BusinessException 圈子或分享不可访问统一返回 404，无撤回权限返回 403
     */
    @Transactional
    public void withdrawShare(UUID circleId, UUID shareId, UUID userId) {
        // 1. 校验访问资格，再读这条分享的历史记录（含已撤回）并校验撤回权限。
        Circle circle = getAccessibleCircle(circleId, userId);
        CircleShare share = circleShareMapper.selectIncludingDeleted(circleId, shareId);
        if (share == null) throw shareUnavailable();
        if (!canWithdraw(share, circle.getCreatorUserId(), userId)) {
            log.warn("撤回分享被拒绝，circleId={}，shareId={}，userId={}", circleId, shareId, userId);
            throw BusinessException.forbidden("只有分享者或圈子创建者可以撤回分享");
        }
        // 2. 已撤回就幂等成功，否则为当前圈子的这条分享写入撤回时间。
        if (share.getDeletedAt() != null) return;
        Instant now = TimeUtils.now();
        circleShareMapper.withdraw(shareId, now);

        // 3. 发布撤回事件，收件箱等下游各自失效。
        events.publishEvent(new CircleShareWithdrawnEvent(circleId, shareId, now));

        log.info("已写入圈子分享撤回，circleId={}，shareId={}，userId={}", circleId, shareId, userId);
    }

    /**
     * 按分享游标分批筛选仍可查看的分享：分享者仍是成员且原内容仍有效。
     * 候选里会混入失效行，每批批量过滤后继续下一批，直到凑足一页或候选耗尽。
     * @param circleId 圈子 ID
     * @param cursor 分页游标，null 表示首屏
     * @param limit 目标行数（页大小加一）
     * @return 有效分享列表，最多 limit 条
     */
    private List<CircleShare> loadSharePage(UUID circleId, CircleShareCursor cursor, int limit) {
        Instant time = cursor == null ? null : cursor.sharedAt();
        UUID id = cursor == null ? null : cursor.id();
        List<CircleShare> rows = new ArrayList<>();
        int batchSize = Math.max(limit, 32);
        while (rows.size() < limit) {
            // 先按排序取候选，批量校验分享者成员关系和原内容存在性，再沿候选末条推进内部游标。
            List<CircleShare> candidates = circleShareMapper.selectPageAfterCursorByCircleId(circleId, time, id, batchSize);
            if (candidates.isEmpty()) break;
            Set<UUID> sharers = circleMemberMapper.selectActiveUserIds(circleId,
                    candidates.stream().map(CircleShare::getSharerUserId).distinct().toList());
            List<UUID> momentIds = objectIds(candidates, ObjectType.moment);
            List<UUID> albumIds = objectIds(candidates, ObjectType.album);
            Set<UUID> moments = momentIds.isEmpty() ? Set.of() : momentMapper.selectByIds(momentIds)
                    .stream().map(Moment::getId).collect(Collectors.toSet());
            Set<UUID> albums = albumIds.isEmpty() ? Set.of() : albumMapper.selectByIds(albumIds)
                    .stream().map(Album::getId).collect(Collectors.toSet());
            candidates.stream().filter(row -> sharers.contains(row.getSharerUserId()))
                    .filter(row -> (row.getObjectType() == ObjectType.moment ? moments : albums)
                            .contains(row.getObjectId()))
                    .limit(limit - rows.size()).forEach(rows::add);
            if (candidates.size() < batchSize) break;
            CircleShare last = candidates.get(candidates.size() - 1);
            time = last.getSharedAt();
            id = last.getId();
        }
        return rows;
    }

    /**
     * 校验圈子访问资格和分享来源，返回已确认有效且类型匹配的分享；原内容由后续步骤加载。
     * @param circleId 请求中的来源圈子 ID
     * @param shareId 请求中的分享 ID
     * @param userId 当前认证用户 ID
     * @param expectedType 入口期望的内容类型
     * @return 分享者仍是有效成员的分享记录
     * @throws BusinessException 圈子或分享不可访问统一返回 404，类型不匹配返回 409
     */
    private CircleShare getAccessibleShare(UUID circleId, UUID shareId, UUID userId,
                                           ObjectType expectedType) {
        // 1. 沿用圈子访问规则，先校验空间和查看者，再限定圈子读取分享历史（含已撤回）。
        getAccessibleCircle(circleId, userId);
        CircleShare share = circleShareMapper.selectIncludingDeleted(circleId, shareId);
        if (share == null || share.getWithdrawnAt() != null
                || !circleMemberMapper.existsByCircleIdAndUserId(circleId, share.getSharerUserId())) {
            throw shareUnavailable();
        }
        // 2. 有效分享走错类型入口时，按类型不匹配返回 409。
        if (share.getObjectType() != expectedType) {
            throw BusinessException.conflict("SHARE_TYPE_MISMATCH",
                    expectedType == ObjectType.moment ? "该分享不是瞬间" : "该分享不是时光");
        }
        return share;
    }

    /**
     * 读取已授权瞬间分享的原内容，并复核当前作者仍是分享者。
     * @param share 已通过访问校验的分享记录
     * @return 原瞬间实体
     * @throws BusinessException 原瞬间缺失或作者已不是分享者时按分享不可访问返回 404
     */
    private Moment getSourceMoment(CircleShare share) {
        // 原瞬间读取一次，后续直接使用该实体加载详情。
        Moment moment = momentMapper.selectById(share.getObjectId());
        if (moment == null || !share.getSharerUserId().equals(moment.getAuthorUserId())) {
            throw shareUnavailable();
        }
        return moment;
    }

    /**
     * 读取已授权时光分享的原内容，并复核当前所有者仍是分享者。
     * @param share 已通过访问校验的分享记录
     * @return 原时光实体
     * @throws BusinessException 原时光缺失或所有者已不是分享者时按分享不可访问返回 404
     */
    private Album getSourceAlbum(CircleShare share) {
        // 有效性由 Mapper 的逻辑删除过滤保证，实体继续交给统计或内部瞬间校验。
        Album album = albumMapper.selectById(share.getObjectId());
        if (album == null || !share.getSharerUserId().equals(album.getOwnerUserId())) {
            throw shareUnavailable();
        }
        return album;
    }

    /**
     * 读取已授权时光中的一个瞬间，限定有效关联且作者为时光所有者。
     * @param album 已通过访问校验的原时光
     * @param momentId 时光内部瞬间 ID
     * @return 有效瞬间实体
     * @throws BusinessException 关联失效、瞬间缺失或作者不是所有者时按分享不可访问返回 404
     */
    private Moment getSourceAlbumMoment(Album album, UUID momentId) {
        // 1. 先检查有效关联，已移出时光的瞬间按分享不可访问处理。
        if (!albumMomentMapper.hasActiveAssociation(album.getId(), momentId)) {
            throw shareUnavailable();
        }
        // 2. 关联有效后读取原瞬间，并确认作者属于时光所有者。
        Moment moment = momentMapper.selectById(momentId);
        if (moment == null || !album.getOwnerUserId().equals(moment.getAuthorUserId())) {
            throw shareUnavailable();
        }
        return moment;
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
        List<UUID> ids = albumMomentMapper.selectActiveLinks(List.of(albumId)).stream()
                .map(AlbumMoment::getMomentId).toList();
        Instant time = cursor == null ? null : cursor.memoryTime();
        UUID id = cursor == null ? null : cursor.id();
        List<Moment> page = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += MOMENT_BATCH_SIZE) {
            List<UUID> batch = ids.subList(start, Math.min(start + MOMENT_BATCH_SIZE, ids.size()));
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
        Map<UUID, UUID> owners = albums.stream().collect(Collectors.toMap(Album::getId, Album::getOwnerUserId));
        List<AlbumMoment> links = albumMomentMapper.selectActiveLinks(List.copyOf(owners.keySet()));
        List<UUID> ids = links.stream().map(AlbumMoment::getMomentId).distinct().toList();
        Map<UUID, UUID> authors = new HashMap<>();
        for (int start = 0; start < ids.size(); start += MOMENT_BATCH_SIZE) {
            List<UUID> batch = ids.subList(start, Math.min(start + MOMENT_BATCH_SIZE, ids.size()));
            for (Moment moment : momentMapper.selectActiveAuthors(batch)) {
                authors.put(moment.getId(), moment.getAuthorUserId());
            }
        }
        return links.stream().filter(link -> owners.get(link.getAlbumId()).equals(authors.get(link.getMomentId())))
                .collect(Collectors.groupingBy(AlbumMoment::getAlbumId, Collectors.counting()));
    }

    /**
     * 校验圈子访问资格和创建者权限，供修改、邀请管理、成员管理和解散共用。
     * @param circleId 圈子 ID
     * @param userId 当前认证用户 ID
     * @return 已确认可访问且本人为创建者的圈子
     * @throws BusinessException 圈子不存在、已解散或非成员统一返回 404，普通成员返回 403
     */
    private Circle getCreatorCircle(UUID circleId, UUID userId) {
        // 沿用圈子详情的访问规则；权限文案统一，不按操作场景特调。
        Circle circle = getAccessibleCircle(circleId, userId);
        if (!userId.equals(circle.getCreatorUserId())) {
            throw BusinessException.forbidden("仅圈子创建者可以操作");
        }
        return circle;
    }

    /**
     * 按邀请码定位可加入的圈子。
     * @param inviteCode 已通过边界校验的长期邀请码
     * @return 未解散且邀请开启的圈子实体
     * @throws BusinessException 邀请码无效或圈子已解散返回 404，邀请关闭返回 409
     */
    private Circle getJoinableCircle(String inviteCode) {
        // 按长期邀请码查找圈子，已解散记录由 Mapper 过滤。
        Circle circle = circleMapper.selectByInviteCode(inviteCode);
        if (circle == null) throw unavailable();
        if (!circle.getInviteEnabled()) {
            throw BusinessException.conflict("INVITATION_DISABLED", "圈子邀请已关闭");
        }
        return circle;
    }

    /**
     * 读取用户的成员关系（含已退出历史）并校验未被限制加入，供加入预览和确认加入共用。
     * @param circleId 圈子 ID
     * @param userId 当前登录用户 ID
     * @return 成员关系；从未加入返回 null，已退出的返回软删历史行
     * @throws BusinessException 被限制再次加入返回 403
     */
    private CircleMember getJoinableMembership(UUID circleId, UUID userId) {
        // 已离开的成员也可能受到加入限制，所以读取含软删的关系再判断限制标记。
        CircleMember membership = circleMemberMapper.selectIncludingDeletedByCircleIdAndUserId(circleId, userId);
        if (membership != null && Boolean.TRUE.equals(membership.getJoinRestricted())) {
            throw BusinessException.forbidden("JOIN_RESTRICTED", "当前用户被限制加入该圈子");
        }
        return membership;
    }

    /**
     * 组装邀请状态响应；关闭邀请时邀请码按对外约定映射为 null，避免继续传播。
     * @param circle 已加载的圈子
     * @return 邀请开关和当前可用邀请码
     */
    private CircleInvitationResponse convertToInvitationResponse(Circle circle) {
        return new CircleInvitationResponse(circle.getInviteEnabled(),
                circle.getInviteEnabled() ? circle.getInviteCode() : null);
    }

    /**
     * 把已加载的圈子、创建者资料和成员列表拼成详情响应，不查库。
     * @param circle 已加载的圈子
     * @param creator 创建者公开资料
     * @param members 已按加入时间排好的成员条目
     * @param isCreator 当前用户是否为创建者
     * @return 详情响应
     */
    private CircleDetailedResponse convertToDetailedResponse(
            Circle circle, PublicUserResponse creator, List<CircleDetailedResponse.Member> members, boolean isCreator) {
        return CircleDetailedResponse.builder()
                .id(circle.getId())
                .name(circle.getName())
                .description(circle.getDescription())
                .creator(creator)
                .memberCount(members.size())
                .isCreator(isCreator)
                .inviteEnabled(circle.getInviteEnabled())
                .createdAt(circle.getCreatedAt())
                .updatedAt(circle.getUpdatedAt())
                .avatarFileId(circle.getAvatarFileId())
                .avatarUrl(loadCircleAvatar(circle))
                .members(members)
                .build();
    }

    /**
     * 批量把成员关系拼成列表摘要：一次补齐圈子主记录和头像后逐条组装。
     *
     * <p>流程：批量读圈子主记录 → 未设置头像的 ID 去重批量签发读取地址 → 保持成员顺序组装摘要。
     * 只读查询，无副作用。</p>
     *
     * @param memberships 成员关系列表，顺序即返回顺序
     * @return 与入参一一对应的摘要列表；头像未设置或失效时回落默认头像
     */
    private List<CircleSummaryResponse> convertToSummaryResponses(List<CircleMember> memberships) {
        // 1. 批量读圈子主记录；解散会同步结束成员关系，主记录缺失属数据异常，大声失败。
        Map<UUID, Circle> circles = circleMapper.selectByIds(
                        memberships.stream().map(CircleMember::getCircleId).toList())
                .stream().collect(Collectors.toMap(Circle::getId, Function.identity()));
        List<UUID> avatarIds = circles.values().stream().map(Circle::getAvatarFileId)
                .filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> avatars = mediaService.getReadUrls(avatarIds);
        String defaultUrl = mediaService.createReadUrl(defaultAvatarKey);
        // 2. 保持成员顺序组装摘要。
        List<CircleSummaryResponse> items = new ArrayList<>();
        for (CircleMember membership : memberships) {
            Circle circle = circles.get(membership.getCircleId());
            if (circle == null) {
                throw new IllegalStateException("圈子主记录缺失：" + membership.getCircleId());
            }
            items.add(convertToSummaryResponse(circle,
                    circle.getAvatarFileId() == null ? defaultUrl
                            : avatars.getOrDefault(circle.getAvatarFileId(), defaultUrl)));
        }
        return items;
    }

    /**
     * 把一个圈子拼成列表摘要卡，不查库。
     * @param circle 圈子实体
     * @param avatarUrl 已签发或回落的头像地址
     * @return 摘要响应
     */
    private CircleSummaryResponse convertToSummaryResponse(Circle circle, String avatarUrl) {
        return new CircleSummaryResponse(circle.getId(), circle.getName(), avatarUrl);
    }

    /**
     * 把圈子资料拼成加入预览的摘要卡，不查库。
     * @param circle 圈子实体
     * @param creator 创建者公开资料
     * @param avatarUrl 已签发或回落的头像地址
     * @return 预览摘要
     */
    private CircleJoinPreviewResponse.CircleSummary convertToJoinSummary(
            Circle circle, PublicUserResponse creator, String avatarUrl) {
        return CircleJoinPreviewResponse.CircleSummary.builder()
                .id(circle.getId())
                .name(circle.getName())
                .description(circle.getDescription())
                .creator(creator)
                .avatarFileId(circle.getAvatarFileId())
                .avatarUrl(avatarUrl)
                .build();
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
    private PublicUserResponse getPublicProfile(Map<UUID, PublicUserResponse> profiles, UUID userId) {
        // 有效成员关系应关联到可读取的用户资料。
        PublicUserResponse profile = profiles.get(userId);
        if (profile == null) throw new IllegalStateException("圈子用户公开资料缺失：" + userId);
        return profile;
    }

    /**
     * 读取用户可访问的有效圈子；不存在、已解散和非成员统一按不可访问处理。
     * @param circleId 圈子 ID
     * @param userId 当前认证用户 ID
     * @return 有效圈子实体
     * @throws BusinessException 三种情况统一返回 404，不暴露圈子是否存在
     */
    private Circle getAccessibleCircle(UUID circleId, UUID userId) {
        Circle circle = circleMapper.selectById(circleId);
        if (circle == null || !circleMemberMapper.existsByCircleIdAndUserId(circleId, userId)) throw unavailable();
        return circle;
    }

    /** 生成圈子不可访问时返回的 404 错误。 */
    private BusinessException unavailable() {
        return BusinessException.notFound("CIRCLE_NOT_FOUND", "圈子不存在或不可访问");
    }

    /** 首次加入时新增成员关系，允许并发重复加入成功。 */
    private void insertJoiningMember(UUID circleId, UUID userId) {
        try {
            insertMembership(circleId, userId, TimeUtils.now());
            log.info("已写入圈子成员关系，circleId={}，userId={}", circleId, userId);
        } catch (DuplicateKeyException conflict) {
            // 唯一键冲突后重新检查；已成为有效成员时按幂等成功处理。
            if (!circleMemberMapper.existsByCircleIdAndUserId(circleId, userId)) {
                throw BusinessException.conflict("RESOURCE_CONFLICT", "圈子成员状态已变化，请重试", conflict);
            }
        }
    }

    /** 新建有效成员关系，调用方负责事务和状态校验。 */
    private void insertMembership(UUID circleId, UUID userId, Instant now) {
        // 为首次加入生成关系 ID，并使用同一 UTC 时间初始化审计字段。
        CircleMember member = new CircleMember();
        member.setId(UuidCreator.getTimeOrderedEpoch());
        member.setCircleId(circleId);
        member.setUserId(userId);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        circleMemberMapper.insert(member);
    }

    /** 检查要分享的瞬间或时光是否存在并属于当前用户。找不到返回 404，属于他人返回 403。 */
    private void validateOwnedObject(CreateCircleSharesRequest request, UUID userId) {
        // 按分享类型查瞬间作者或时光所有者。
        UUID owner;
        if (request.objectType() == ObjectType.moment) {
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
    private CircleShare newShare(CreateCircleSharesRequest request, UUID circleId, UUID userId, Instant now) {
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
    private void persistChanges(List<CircleShare> additions, List<UUID> restores, UUID userId, Instant now) {
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

    /**
     * 为本页分享组装列表条目：分别批量加载原内容和分享者资料，再按查询顺序拼装。
     * @param page 本页已过滤有效的分享记录
     * @param creatorId 圈子创建者 ID，用于计算撤回权限
     * @param userId 当前认证用户 ID，用于计算撤回权限
     * @return 与 page 一一对应的条目
     */
    private List<CircleShareFeedResponse> loadFeed(List<CircleShare> page, UUID creatorId, UUID userId) {
        // 1. 空页不访问任何服务；按类型拆出原内容 ID，分别批量加载瞬间详情、时光卡片和分享者资料。
        if (page.isEmpty()) return List.of();
        List<UUID> momentIds = objectIds(page, ObjectType.moment);
        List<UUID> albumIds = objectIds(page, ObjectType.album);
        Map<UUID, MomentDetailedResponse> momentResponses = momentIds.isEmpty() ? Map.of()
                : momentService.loadAuthorizedResponses(momentMapper.selectByIds(momentIds)).stream()
                .collect(Collectors.toMap(MomentDetailedResponse::id, Function.identity()));
        Map<UUID, CircleSharedAlbumResponse> albumResponses = loadAlbumCards(albumIds);
        Map<UUID, PublicUserResponse> profiles = loadSharers(page);
        // 2. 按分享顺序组装条目；原内容在批量加载中缺失（并发失效）时按分享不可访问处理。
        return page.stream()
                .map(row -> convertToFeedResponse(row, creatorId, userId, profiles, momentResponses, albumResponses))
                .toList();
    }

    /**
     * 把单条分享拼成 Feed 条目：按分享类型填 moment 或 album 其中之一。
     * @param row 已过滤有效的分享记录
     * @param creatorId 圈子创建者 ID
     * @param userId 当前认证用户 ID
     * @param profiles 已加载的分享者公开资料
     * @param momentResponses 已加载的瞬间详情，按瞬间 ID 索引
     * @param albumResponses 已加载的时光卡片，按时光 ID 索引
     * @return 一条分享流条目
     */
    private CircleShareFeedResponse convertToFeedResponse(
            CircleShare row,
            UUID creatorId,
            UUID userId,
            Map<UUID, PublicUserResponse> profiles,
            Map<UUID, MomentDetailedResponse> momentResponses,
            Map<UUID, CircleSharedAlbumResponse> albumResponses) {

        // 分享者资料缺失按数据异常大声失败。
        PublicUserResponse sharer = profiles.get(row.getSharerUserId());
        if (sharer == null) {
            throw new IllegalStateException("分享者公开资料缺失：" + row.getId());
        }

        // 按类型取原内容卡片；类型对应的卡片缺失说明原内容在加载间隙失效。
        MomentDetailedResponse moment = momentResponses.get(row.getObjectId());
        CircleSharedAlbumResponse album = albumResponses.get(row.getObjectId());

        boolean isMoment = row.getObjectType() == ObjectType.moment;
        boolean isAlbum = row.getObjectType() == ObjectType.album;

        if ((isMoment && moment == null) || (isAlbum && album == null)) {
            throw shareUnavailable();
        }

        return CircleShareFeedResponse.builder()
                .id(row.getId())
                .circleId(row.getCircleId())
                .objectType(row.getObjectType())
                .objectId(row.getObjectId())
                .sharer(sharer)
                .sharedAt(row.getSharedAt())
                .updatedAt(row.getUpdatedAt())
                .canWithdraw(canWithdraw(row, creatorId, userId))
                .moment(isMoment ? moment : null)
                .album(isAlbum ? album : null)
                .build();
    }

    /** 取出本页指定类型的原内容 ID，并去掉重复 ID。 */
    private List<UUID> objectIds(List<CircleShare> page, ObjectType type) {
        return page.stream().filter(row -> row.getObjectType() == type).map(CircleShare::getObjectId).distinct().toList();
    }

    /** 为已通过权限检查的时光组装卡片，返回以时光 ID 为键的 Map。 */
    private Map<UUID, CircleSharedAlbumResponse> loadAlbumCards(List<UUID> ids) {
        // 1. 批量查询时光、当前瞬间数量和可用的封面文件。
        if (ids.isEmpty()) return Map.of();
        List<Album> rows = albumMapper.selectByIds(ids);
        Map<UUID, Long> counts = countSharedAlbumMoments(rows);
        Map<UUID, String> coverUrls = mediaService.getReadUrls(rows.stream().map(Album::getCoverFileId).filter(Objects::nonNull).distinct().toList());
        // 2. 封面不可用时填 null，没有瞬间时填 0。
        return rows.stream().collect(Collectors.toMap(Album::getId, album -> {
            return CircleSharedAlbumResponse.builder()
                    .id(album.getId())
                    .name(album.getName())
                    .description(album.getDescription())
                    .coverUrl(album.getCoverFileId() == null ? null : coverUrls.get(album.getCoverFileId()))
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

    // TODO 游标时间缺范围校验：cursorTime 的 1000～9999 年份校验随换算删除而失效，超范围游标会直打数据库变 500，需补 400 参数校验

}
