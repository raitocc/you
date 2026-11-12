package com.you.youbackend.gathering.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.entity.GatheringMember;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.FaceToFaceRules;
import com.you.youbackend.gathering.model.GatheringCount;
import com.you.youbackend.gathering.model.GatheringListCursor;
import com.you.youbackend.gathering.model.GatheringMemberCursor;
import com.you.youbackend.gathering.model.GatheringStatus;
import com.you.youbackend.gathering.request.AddGatheringMomentsRequest;
import com.you.youbackend.gathering.request.CreateGatheringRequest;
import com.you.youbackend.gathering.request.GatheringJoinPreviewRequest;
import com.you.youbackend.gathering.request.JoinFaceToFaceRequest;
import com.you.youbackend.gathering.request.JoinGatheringRequest;
import com.you.youbackend.gathering.request.RemoveGatheringMomentsRequest;
import com.you.youbackend.gathering.request.UpdateGatheringRequest;
import com.you.youbackend.gathering.response.GatheringJoinPreviewResponse;
import com.you.youbackend.gathering.response.GatheringResponse;
import com.you.youbackend.gathering.response.GatheringSummaryResponse;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 同行创建、成员加入与瞬间关联资格校验。 */
@Log4j2
@Service
@RequiredArgsConstructor
public class GatheringService {
    /** 列表介绍摘要的最大 Unicode 码点数。 */
    private static final int SUMMARY_DESCRIPTION_LENGTH = 100;
    /** 长期邀请码长度，与 gathering.invite_code 的字段长度一致。 */
    private static final int INVITE_CODE_LENGTH = 16;
    /** 使用大写字母与数字，避免 MySQL 默认忽略大小写导致随机空间缩水。 */
    private static final String INVITE_CODE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom INVITE_CODE_RANDOM = new SecureRandom();

    private final GatheringMapper gatheringMapper;
    private final GatheringMemberMapper gatheringMemberMapper;
    private final UserService userService;
    private final MomentMapper momentMapper;

    /**
     * 普通成员主动退出并解除本人全部瞬间关联，两个写操作在同一事务中完成。
     * @param gatheringId 未解散的同行 ID，允许已结束
     * @param userId 当前认证用户 ID，创建者须选择解散
     */
    @Transactional
    public void leaveGathering(UUID gatheringId, UUID userId) {
        // 1. 同行须有效，创建者保留管理责任，通过解散关闭同行。
        Gathering gathering = requireGathering(gatheringId);
        if (userId.equals(gathering.getCreatorUserId())) {
            log.warn("同行退出被拒绝，gatheringId={}，userId={}，reason=创建者须解散同行", gatheringId, userId);
            throw BusinessException.badRequest("GATHERING_CREATOR_CANNOT_LEAVE", "创建者无法直接退出，请选择解散同行");
        }
        // 2. 区分从未加入与已经退出，重复退出保持成功且不修改时间。
        GatheringMember member = gatheringMemberMapper.selectIncludingDeleted(gatheringId, userId);
        if (member == null) {
            log.warn("同行退出被拒绝，gatheringId={}，userId={}，reason=从未加入", gatheringId, userId);
            throw BusinessException.forbidden("当前用户未加入该同行");
        }
        if (member.getDeletedAt() != null) {
            log.debug("用户已退出同行，gatheringId={}，userId={}", gatheringId, userId);
            return;
        }
        // 3. 成员退出和本人瞬间解绑共同提交，原始瞬间与媒体保留。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        gatheringMemberMapper.leaveGathering(member.getId(), now);
        int removed = momentMapper.removeAllOwnedGathering(gatheringId, userId, now);
        log.info("已写入同行退出，gatheringId={}，userId={}，removedMomentCount={}", gatheringId, userId, removed);
    }

    /**
     * 创建者结束同行并关闭邀请，原有成员与瞬间关联保留。
     * @param gatheringId 有效同行 ID
     * @param userId 当前认证用户 ID
     */
    @Transactional
    public void endGathering(UUID gatheringId, UUID userId) {
        // 1. 校验有效同行和创建者身份，重复结束保持成功。
        Gathering gathering = requireGathering(gatheringId);
        requireCreator(gathering, userId);
        if (gathering.getStatus() == GatheringStatus.ENDED) {
            log.debug("同行已结束，gatheringId={}，userId={}", gatheringId, userId);
            return;
        }
        // 2. 状态与邀请开关一起更新，各加入入口继续复用邀请检查。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        gatheringMapper.endGathering(gatheringId, now);
        log.info("已写入同行结束，gatheringId={}，userId={}", gatheringId, userId);
    }

    /**
     * 创建者解散同行并解除全部瞬间关联，同一事务提交，成员记录和原始瞬间保留。
     * @param gatheringId 同行 ID，已解散记录用于识别重复请求
     * @param userId 当前认证用户 ID
     */
    @Transactional
    public void dissolveGathering(UUID gatheringId, UUID userId) {
        // 1. 包含已解散记录查询，先校验创建者，再识别重复请求。
        Gathering gathering = gatheringMapper.selectIncludingDeleted(gatheringId);
        if (gathering == null) {
            throw BusinessException.notFound("GATHERING_NOT_FOUND", "同行不存在或已解散");
        }
        requireCreator(gathering, userId);
        if (gathering.getDeletedAt() != null) {
            log.debug("同行已解散，gatheringId={}，userId={}", gatheringId, userId);
            return;
        }
        // 2. 关闭同行入口并批量解除关联，任一步骤失败整体回滚。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        gatheringMapper.dissolveGathering(gatheringId, now);
        int removed = momentMapper.removeAllGathering(gatheringId, now);
        log.info("已写入同行解散，gatheringId={}，userId={}，removedMomentCount={}", gatheringId, userId, removed);
    }

    /**
     * 批量关联本人瞬间，整批校验与写入共同提交或回滚。
     * @param gatheringId 进行中的目标同行 ID
     * @param request 已通过列表及 UUIDv7 校验的请求
     * @param userId 当前认证用户 ID
     */
    @Transactional
    public void addMoments(UUID gatheringId, AddGatheringMomentsRequest request, UUID userId) {
        // 1. 复用发布时的关联资格校验，批量加载去重后的本人有效瞬间。
        validateMomentAssociation(gatheringId, userId);
        List<Moment> moments = requireOwnedMoments(request.momentIds(), userId);
        // 2. 任何其他同行关联均使整批失败，已关联当前同行的按成功处理。
        validateNoOtherGathering(moments, gatheringId);
        List<UUID> pendingIds = moments.stream().filter(moment -> moment.getGatheringId() == null)
                .map(Moment::getId).toList();
        if (pendingIds.isEmpty()) {
            log.debug("瞬间已全部关联当前同行，gatheringId={}，userId={}，count={}", gatheringId, userId, moments.size());
            return;
        }
        // 3. 只更新新增关联；条件更新与行数检查共同保证不会覆盖其他请求建立的关联。
        LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        int changed = momentMapper.associateGathering(userId, gatheringId, pendingIds, updatedAt);
        if (changed != pendingIds.size()) {
            throw BusinessException.conflict("MOMENT_ASSOCIATION_CHANGED", "瞬间关联状态已变化，请刷新后重试");
        }
        log.info("已写入瞬间批量关联，gatheringId={}，userId={}，count={}", gatheringId, userId, changed);
    }

    /**
     * 批量取消本人瞬间在指定同行中的关联，允许已结束同行，重复取消成功。
     * @param gatheringId 当前操作的同行 ID
     * @param request 已通过列表及 UUIDv7 校验的请求
     * @param userId 当前认证用户 ID
     */
    @Transactional
    public void removeMoments(UUID gatheringId, RemoveGatheringMomentsRequest request, UUID userId) {
        // 1. 校验有效同行及本人成员资格，整批确认瞬间有效且属于本人。
        requireMemberGathering(gatheringId, userId);
        List<Moment> moments = requireOwnedMoments(request.momentIds(), userId);
        // 2. 仅筛选当前同行的关联，未关联和其他同行关联保持原样。
        List<UUID> pendingIds = moments.stream().filter(moment -> gatheringId.equals(moment.getGatheringId()))
                .map(Moment::getId).toList();
        if (pendingIds.isEmpty()) {
            log.debug("指定瞬间已移出同行，gatheringId={}，userId={}，count={}", gatheringId, userId, moments.size());
            return;
        }
        // 3. 一次清空符合条件的关联，原瞬间及媒体内容保留。
        LocalDateTime updatedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        int changed = momentMapper.removeGathering(userId, gatheringId, pendingIds, updatedAt);
        log.info("已写入瞬间批量取消关联，gatheringId={}，userId={}，count={}", gatheringId, userId, changed);
    }

    /**
     * 浏览本人已加入的有效同行，分页后批量补齐数量。
     * @param request 已由 PageQuery 校验的分页参数
     * @param userId 当前认证用户 ID
     * @return 按创建时间、ID 倒序的同行摘要页
     */
    @Transactional(readOnly = true)
    public ResultPage<GatheringSummaryResponse, GatheringListCursor> browseGatherings(
            PageRequest<GatheringListCursor> request, UUID userId) {
        // 1. 按游标加载页大小加一条，成员与同行都必须有效。
        List<Gathering> rows = findGatheringPage(request, userId);
        boolean hasMore = rows.size() > request.limit();
        List<Gathering> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        // 2. 只为实际返回的本页记录批量加载创建者资料和数量，再转换为摘要。
        List<GatheringSummaryResponse> items = buildSummaries(page);
        GatheringListCursor nextCursor = null;
        if (hasMore) {
            Gathering last = page.get(page.size() - 1);
            nextCursor = new GatheringListCursor(last.getCreatedAt().toInstant(ZoneOffset.UTC), last.getId());
        }
        log.debug("浏览本人同行，userId={}，count={}，hasMore={}", userId, items.size(), hasMore);
        return new ResultPage<>(items, nextCursor, hasMore);
    }

    /**
     * 有效成员读取同行详情，分页内容从各自入口读取。
     * @param gatheringId 同行 ID
     * @param userId 当前认证用户 ID
     * @return 同行信息、实际数量与创建者公开资料
     */
    @Transactional(readOnly = true)
    public GatheringResponse getGathering(UUID gatheringId, UUID userId) {
        // 先校验空间及成员资格，再加载完整展示信息。
        Gathering gathering = requireMemberGathering(gatheringId, userId);
        log.debug("查看同行信息，gatheringId={}，userId={}", gatheringId, userId);
        return loadMemberGatheringResponse(gathering);
    }

    /**
     * 创建者全量修改基本信息，保存及响应加载在同一事务完成。
     * @param gatheringId 同行 ID
     * @param request 已校验名称、介绍，null 介绍表示清空
     * @param userId 当前认证用户 ID
     * @return 最新同行信息
     */
    @Transactional
    public GatheringResponse updateGathering(UUID gatheringId, UpdateGatheringRequest request, UUID userId) {
        // 1. 校验有效同行和创建者权限，管理身份由创建者字段确定。
        Gathering gathering = requireGathering(gatheringId);
        if (!gathering.getCreatorUserId().equals(userId)) {
            log.warn("修改同行被拒绝，gatheringId={}，userId={}，reason=非创建者", gatheringId, userId);
            throw BusinessException.forbidden("只有创建者可以修改同行信息");
        }
        // 2. 显式保存允许编辑的字段，介绍 null 会清空数据库原值。
        gathering.setName(request.name());
        gathering.setDescription(request.description());
        gathering.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS));
        gatheringMapper.updateContent(gathering);
        // 3. 响应加载失败时写入回滚，日志表示当前事务内完成写入。
        GatheringResponse response = loadMemberGatheringResponse(gathering);
        log.info("已写入同行基本信息修改，gatheringId={}，userId={}", gatheringId, userId);
        return response;
    }

    /**
     * 有效成员分页读取其他有效成员的公开资料。
     * @param gatheringId 同行 ID
     * @param request 已校验的成员关系创建时间、用户 ID 游标
     * @param userId 当前认证用户 ID
     * @return 按成员关系创建时间、用户 ID 正序的公开资料页
     */
    @Transactional(readOnly = true)
    public ResultPage<PublicUserResponse, GatheringMemberCursor> browseMembers(
            UUID gatheringId, PageRequest<GatheringMemberCursor> request, UUID userId) {
        // 1. 校验同行和访问资格，再按关系原创建时间分页。
        requireMemberGathering(gatheringId, userId);
        List<GatheringMember> rows = findMemberPage(gatheringId, request);
        boolean hasMore = rows.size() > request.limit();
        List<GatheringMember> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        // 2. 批量加载资料，保留关系顺序；查不到的资料保留为 null。
        List<UUID> userIds = page.stream().map(GatheringMember::getUserId).toList();
        Map<UUID, PublicUserResponse> profiles = userService.getPublicUsers(userIds);
        List<PublicUserResponse> items = userIds.stream().map(profiles::get).toList();
        GatheringMemberCursor nextCursor = null;
        if (hasMore) {
            GatheringMember last = page.get(page.size() - 1);
            nextCursor = new GatheringMemberCursor(last.getCreatedAt().toInstant(ZoneOffset.UTC), last.getUserId());
        }
        log.debug("浏览同行成员，gatheringId={}，userId={}，count={}，hasMore={}",
                gatheringId, userId, items.size(), hasMore);
        return new ResultPage<>(items, nextCursor, hasMore);
    }

    /**
     * 创建同行与创建者成员关系，两类记录共同提交或回滚。
     * @param request 已通过名称及介绍校验的请求
     * @param userId 当前登录用户 ID，由统一认证过滤器提供
     * @return 初始成员数为 1、瞬间数为 0 的同行信息，包含长期邀请码
     */
    @Transactional
    public GatheringResponse createGathering(CreateGatheringRequest request, UUID userId) {
        // 1. 确认当前账号有效，并取得用于响应的公开资料与头像签名。
        PublicUserResponse creator = userService.getPublicUser(userId);
        LocalDateTime savedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        // 2. 保存同行和创建者成员关系，任一步失败均回滚整个创建过程。
        Gathering gathering = saveNewGathering(request, userId, savedAt);
        saveCreatorMembership(gathering.getId(), userId, savedAt);
        // 3. 使用已知初始数量组装响应，日志表达事务内已完成写入。
        GatheringResponse response = toResponse(gathering, creator, 1, 0);
        log.info("已写入同行及创建者成员关系，gatheringId={}，userId={}", gathering.getId(), userId);
        return response;
    }

    /**
     * 根据长期邀请码预览加入目标，整个流程只读取数据。
     * @param request 已通过邀请码校验的查询参数
     * @param userId 当前登录用户 ID
     * @return 目标基本信息、创建者公开资料和当前有效成员标记
     * @throws BusinessException 邀请码不存在、同行已解散或邀请关闭时返回 404
     */
    @Transactional(readOnly = true)
    public GatheringJoinPreviewResponse previewJoin(GatheringJoinPreviewRequest request, UUID userId) {
        // 1. 根据当前邀请码定位未解散且开放邀请的同行，已结束仍允许加入。
        Gathering gathering = requireJoinableGathering(request.inviteCode());
        // 2. 查询有效成员关系与创建者资料，供前端选择进入或确认加入。
        boolean isMember = gatheringMemberMapper.isActiveMember(gathering.getId(), userId);
        PublicUserResponse creator = userService.getPublicUser(gathering.getCreatorUserId());
        log.debug("预览同行加入，gatheringId={}，userId={}，isMember={}", gathering.getId(), userId, isMember);
        return new GatheringJoinPreviewResponse(gathering.getId(), gathering.getName(),
                gathering.getDescription(), creator, isMember);
    }

    /**
     * 确认加入同行：按提交时状态校验，建立或恢复成员关系后返回最新同行信息。
     * @param request 已通过邀请码校验的请求，支持直接调用
     * @param userId 当前登录用户 ID，由统一认证过滤器提供
     * @return 当前同行信息和实际成员、瞬间数量；已是有效成员时返回现有结果
     * @throws BusinessException 邀请码不存在、同行已解散或邀请关闭时返回 404
     */
    @Transactional
    public GatheringResponse joinGathering(JoinGatheringRequest request, UUID userId) {
        // 1. 使用已认证身份，重新读取邀请码与邀请开关，不依赖先前预览。
        Gathering gathering = requireJoinableGathering(request.inviteCode());
        // 2. 已是成员时保持原样，否则新增或恢复唯一关系；原瞬间关联保持原样。
        ensureMembership(gathering.getId(), userId);
        // 3. 读取当前有效数量与创建者资料，响应组装失败时成员写入一并回滚。
        return loadGatheringResponse(gathering);
    }

    /**
     * 凭同行 ID 和短码确认加入，全部校验与成员写入在一个数据库事务内完成。
     * <p>流程：查询并校验目标 → 新增、恢复或沿用成员关系 → 组装当前同行信息。
     * 附近预览只帮助发现目标，本方法直接根据数据库短码、邀请开关和创建时间判断加入资格。
     * @param request 已校验的同行 ID 与四位短码
     * @param userId 统一认证过滤器提供的用户 ID
     * @return 加入后的同行信息，重复加入保持原关系
     */
    @Transactional
    public GatheringResponse joinFromFaceToFace(JoinFaceToFaceRequest request, UUID userId) {
        // 1. 一次查询校验目标，确认短码一致且邀请仍在有效时间内。
        Gathering gathering = requireFaceToFaceGathering(request.gatheringId(), request.faceCode());
        // 2. 复用成员新增与恢复规则，已有有效成员关系保持原样。
        ensureMembership(gathering.getId(), userId);
        // 3. 使用已加载的同行组装响应，成员写入与响应加载共同成功或回滚。
        return loadGatheringResponse(gathering);
    }

    /**
     * 校验用户能否向指定同行关联本人瞬间，由发布等业务在写入前调用。
     * @param gatheringId 请求中已提供的同行 ID
     * @param userId 当前登录用户 ID
     * @throws BusinessException 同行不存在或已解散返回 404，已结束返回 400，非有效成员返回 403
     */
    public void validateMomentAssociation(UUID gatheringId, UUID userId) {
        // 1. 查询有效同行，已解散记录由全局软删除配置过滤。
        Gathering gathering = gatheringMapper.selectById(gatheringId);
        if (gathering == null) {
            log.debug("关联瞬间被拒绝，gatheringId={}，userId={}，reason=同行不存在或已解散", gatheringId, userId);
            throw BusinessException.notFound("GATHERING_NOT_FOUND", "同行不存在或已解散");
        }
        // 2. 同行结束后保留原有内容，停止新增关联。
        if (gathering.getStatus() == GatheringStatus.ENDED) {
            log.debug("关联瞬间被拒绝，gatheringId={}，userId={}，reason=同行已结束", gatheringId, userId);
            throw BusinessException.badRequest("GATHERING_ENDED", "同行已结束，无法添加瞬间");
        }
        // 3. 发布资格取决于有效成员关系，邀请开关仅控制新成员加入。
        if (!gatheringMemberMapper.isActiveMember(gatheringId, userId)) {
            log.warn("关联瞬间被拒绝，gatheringId={}，userId={}，reason=非有效成员", gatheringId, userId);
            throw BusinessException.forbidden("只有同行有效成员可以添加瞬间");
        }
    }

    /**
     * 去重并批量确认瞬间有效且属于本人，供关联及取消关联共同使用。
     * @param momentIds 已校验非空、元素非空的瞬间 ID
     * @param userId 当前作者 ID
     * @return 只含关联校验字段的瞬间列表，候选全部通过校验
     */
    private List<Moment> requireOwnedMoments(List<UUID> momentIds, UUID userId) {
        // 查询限定作者和有效记录，返回数量少于去重候选即表示整批中存在无效项。
        List<UUID> ids = momentIds.stream().distinct().toList();
        List<Moment> moments = momentMapper.selectOwnedForGathering(userId, ids);
        if (moments.size() != ids.size()) {
            log.warn("批量关联操作被拒绝，userId={}，reason=瞬间不存在、已删除或非本人", userId);
            throw BusinessException.invalidArgument("部分瞬间不存在、已删除或不属于本人");
        }
        return moments;
    }

    /**
     * 确认整批瞬间均未关联其他同行，写入前调用。
     * @param moments 已确认有效且属于本人的瞬间关联投影
     * @param gatheringId 本次目标同行 ID
     */
    private void validateNoOtherGathering(List<Moment> moments, UUID gatheringId) {
        // 已关联当前同行属于重复操作；其他同行关联必须先由用户主动解除。
        boolean hasOtherGathering = moments.stream().anyMatch(moment -> moment.getGatheringId() != null
                && !gatheringId.equals(moment.getGatheringId()));
        if (hasOtherGathering) {
            log.debug("批量关联被拒绝，gatheringId={}，reason=部分瞬间已关联其他同行", gatheringId);
            throw BusinessException.conflict("MOMENT_ALREADY_ASSOCIATED", "部分瞬间已关联其他同行，请先取消原关联");
        }
    }

    /**
     * 读取有效同行并校验当前用户成员资格，供同行和内容读取入口共用。
     * @param gatheringId 同行 ID
     * @param userId 当前认证用户 ID
     * @return 已确认有效且本人为有效成员的同行
     */
    public Gathering requireMemberGathering(UUID gatheringId, UUID userId) {
        // 先确认同行有效，再确认当前关系，邀请开关及结束状态均不影响已加入成员查看。
        Gathering gathering = requireGathering(gatheringId);
        if (!gatheringMemberMapper.isActiveMember(gatheringId, userId)) {
            log.warn("访问同行被拒绝，gatheringId={}，userId={}，reason=非有效成员", gatheringId, userId);
            throw BusinessException.forbidden("只有同行有效成员可以查看");
        }
        return gathering;
    }

    /** 按首屏或后续页意图读取本人同行，分页参数已由请求边界校验。 */
    private List<Gathering> findGatheringPage(PageRequest<GatheringListCursor> request, UUID userId) {
        // 将游标转换为数据库 UTC 时间，首屏不提供边界。
        GatheringListCursor cursor = request.cursor();
        LocalDateTime cursorTime = cursor == null ? null : LocalDateTime.ofInstant(cursor.createdAt(), ZoneOffset.UTC);
        UUID cursorId = cursor == null ? null : cursor.id();
        return gatheringMapper.selectPage(userId, cursorTime, cursorId, request.limit() + 1);
    }

    /** 为非空本页批量加载创建者资料、成员数及瞬间数，空页直接返回空列表。 */
    private List<GatheringSummaryResponse> buildSummaries(List<Gathering> page) {
        // 零记录时跳过 IN 查询；无统计行表示当前数量为零。
        if (page.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = page.stream().map(Gathering::getId).toList();
        Map<UUID, Long> memberCounts = toCountMap(gatheringMemberMapper.countByGatherings(ids));
        Map<UUID, Long> momentCounts = toCountMap(momentMapper.countByGatherings(ids));
        List<UUID> creatorIds = page.stream().map(Gathering::getCreatorUserId).distinct().toList();
        Map<UUID, PublicUserResponse> creators = userService.getPublicUsers(creatorIds);
        return page.stream().map(gathering -> toSummaryResponse(gathering,
                creators.get(gathering.getCreatorUserId()),
                memberCounts.getOrDefault(gathering.getId(), 0L),
                momentCounts.getOrDefault(gathering.getId(), 0L))).toList();
    }

    /** 将数据库分组统计投影转换为同行 ID 到数量的动态映射。 */
    private Map<UUID, Long> toCountMap(List<GatheringCount> counts) {
        // 每个同行由 GROUP BY 返回一行，无记录的同行在组装时解释为零。
        return counts.stream().collect(Collectors.toMap(GatheringCount::getGatheringId, GatheringCount::getCount));
    }

    /** 仅转换已加载的同行、创建者资料和数量，介绍截取前 100 个 Unicode 码点，保留原实体。 */
    private GatheringSummaryResponse toSummaryResponse(
            Gathering gathering, PublicUserResponse creator, long memberCount, long momentCount) {
        // 使用码点边界保留完整 emoji，null 介绍保持为空。
        String description = gathering.getDescription();
        if (description != null) {
            int end = description.offsetByCodePoints(0, Math.min(SUMMARY_DESCRIPTION_LENGTH, description.codePointCount(0, description.length())));
            description = description.substring(0, end);
        }
        return new GatheringSummaryResponse(gathering.getId(), gathering.getName(), description,
                creator, memberCount, momentCount, gathering.getCreatedAt().toInstant(ZoneOffset.UTC));
    }

    /** 校验已查询同行的创建者身份，供结束、解散操作使用。 */
    private void requireCreator(Gathering gathering, UUID userId) {
        // 创建者身份来自同行主记录，其他成员无管理权限。
        if (!userId.equals(gathering.getCreatorUserId())) {
            log.warn("同行管理被拒绝，gatheringId={}，userId={}，reason=非创建者", gathering.getId(), userId);
            throw BusinessException.forbidden("仅创建者可以操作");
        }
    }

    /** 读取有效同行，软删除由全局配置过滤，缺失返回 404。 */
    private Gathering requireGathering(UUID gatheringId) {
        // 同行不存在和已解散使用统一业务语义。
        Gathering gathering = gatheringMapper.selectById(gatheringId);
        if (gathering == null) {
            throw BusinessException.notFound("GATHERING_NOT_FOUND", "同行不存在或已解散");
        }
        return gathering;
    }

    /** 根据已校验的分页参数读取完整成员关系，供后续批量补齐资料。 */
    private List<GatheringMember> findMemberPage(UUID gatheringId, PageRequest<GatheringMemberCursor> request) {
        // 将游标转换为数据库 UTC 时间，首屏不提供边界。
        GatheringMemberCursor cursor = request.cursor();
        LocalDateTime cursorTime = cursor == null ? null : LocalDateTime.ofInstant(cursor.createdAt(), ZoneOffset.UTC);
        UUID cursorUserId = cursor == null ? null : cursor.userId();
        return gatheringMemberMapper.selectPage(gatheringId, cursorTime, cursorUserId, request.limit() + 1);
    }

    /** 已校验访问权限后，加载详情所需创建者及数量，创建者资料查不到时返回 null。 */
    private GatheringResponse loadMemberGatheringResponse(Gathering gathering) {
        // 创建者资料使用批量查询，查不到时 creator 为 null，同行信息仍正常返回。
        PublicUserResponse creator = userService.getPublicUsers(List.of(gathering.getCreatorUserId())).get(gathering.getCreatorUserId());
        long memberCount = gatheringMemberMapper.countActiveMembers(gathering.getId());
        long momentCount = momentMapper.countByGathering(gathering.getId());
        return toResponse(gathering, creator, memberCount, momentCount);
    }

    /**
     * 从数据库校验面对面加入资格。
     * @param gatheringId 用户选择的同行 ID
     * @param faceCode 用户提交的四位短码
     * @return 短码匹配、邀请开启且创建未满 10 分钟的同行
     */
    private Gathering requireFaceToFaceGathering(UUID gatheringId, String faceCode) {
        // 同行软删除由 Mapper 过滤，短码永久保存但仅在创建后 10 分钟内可用于加入。
        Gathering gathering = gatheringMapper.selectById(gatheringId);
        Instant now = Instant.now();
        if (gathering == null || !Boolean.TRUE.equals(gathering.getInviteEnabled())
                || !faceCode.equals(gathering.getFaceCode())
                || !gathering.getCreatedAt().toInstant(ZoneOffset.UTC).plus(FaceToFaceRules.WINDOW).isAfter(now)) {
            log.debug("面对面加入校验失败，gatheringId={}，reason=同行失效、邀请关闭、短码不匹配或已过期", gatheringId);
            throw BusinessException.notFound("FACE_TO_FACE_INVITATION_UNAVAILABLE", "同行不可加入、短码不匹配或邀请已过期");
        }
        return gathering;
    }

    /**
     * 根据邀请码加载开放邀请的同行；结束操作会关闭邀请。
     * @param inviteCode 已通过边界校验的长期邀请码
     * @return 未解散且开放邀请的完整同行实体
     * @throws BusinessException 目标不可加入时统一返回 404
     */
    private Gathering requireJoinableGathering(String inviteCode) {
        // 邀请可用性取决于同行仍有效且邀请开启，软删除由 Mapper 过滤。
        Gathering gathering = gatheringMapper.selectByInviteCode(inviteCode);
        if (gathering == null || !Boolean.TRUE.equals(gathering.getInviteEnabled())) {
            throw BusinessException.notFound("GATHERING_INVITATION_UNAVAILABLE", "邀请码无效、邀请已关闭或同行已解散");
        }
        return gathering;
    }

    /**
     * 确保用户拥有有效成员关系，须在加入业务事务内调用。
     * @param gatheringId 已校验可加入的同行 ID
     * @param userId 统一认证过滤器提供的当前用户 ID
     */
    private void ensureMembership(UUID gatheringId, UUID userId) {
        // 1. 普通重复请求直接使用现有关系，审计时间保持原样。
        if (gatheringMemberMapper.isActiveMember(gatheringId, userId)) {
            log.debug("用户已在同行中，跳过重复加入，gatheringId={}，userId={}", gatheringId, userId);
            return;
        }
        // 2. 使用唯一索引完成新增或恢复，重叠请求也只保留一条关系。
        LocalDateTime savedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        gatheringMemberMapper.joinOrRestore(newMembership(gatheringId, userId, savedAt));
        log.info("已写入同行成员加入，gatheringId={}，userId={}", gatheringId, userId);
    }

    /**
     * 加载供有效成员查看的同行信息，调用方已完成业务访问校验。
     * @param gathering 已确认有效且当前用户可访问的同行
     * @return 包含实际数量与创建者公开资料的同行信息
     */
    private GatheringResponse loadGatheringResponse(Gathering gathering) {
        // 从各自 Mapper 统计有效记录，复用公开资料和统一响应组装。
        PublicUserResponse creator = userService.getPublicUser(gathering.getCreatorUserId());
        long memberCount = gatheringMemberMapper.countActiveMembers(gathering.getId());
        long momentCount = momentMapper.countByGathering(gathering.getId());
        return toResponse(gathering, creator, memberCount, momentCount);
    }

    /**
     * 保存进行中且开放邀请的同行。
     * @param request 已校验并整理名称的请求
     * @param userId 已确认有效的创建者 ID
     * @param savedAt 本次写入统一使用的 UTC 毫秒时间
     * @return 已保存的同行，邀请码冲突时抛出业务异常供客户端重试
     */
    private Gathering saveNewGathering(CreateGatheringRequest request, UUID userId, LocalDateTime savedAt) {
        // 1. 身份、UUIDv7、初始状态和长期邀请码均由服务端确定。
        Gathering gathering = new Gathering();
        gathering.setId(UuidCreator.getTimeOrderedEpoch());
        gathering.setCreatorUserId(userId);
        gathering.setName(request.name());
        gathering.setDescription(request.description());
        gathering.setStatus(GatheringStatus.ONGOING);
        gathering.setInviteEnabled(true);
        gathering.setInviteCode(generateInviteCode());
        gathering.setCreatedAt(savedAt);
        gathering.setUpdatedAt(savedAt);
        // 2. 以唯一索引作为最终约束，冲突后终止本次事务，提示重新创建。
        try {
            gatheringMapper.insert(gathering);
        } catch (DuplicateKeyException exception) {
            log.warn("创建同行被拒绝，userId={}，reason=生成标识冲突", userId);
            throw BusinessException.conflict("GATHERING_CREATE_CONFLICT", "同行标识生成冲突，请重新创建", exception);
        }
        return gathering;
    }

    /**
     * 保存创建者的有效成员关系，须在创建同行的事务内调用。
     * @param gatheringId 当前事务中新建的同行 ID
     * @param userId 已确认有效的创建者 ID
     * @param savedAt 与同行主记录一致的 UTC 毫秒时间
     */
    private void saveCreatorMembership(UUID gatheringId, UUID userId, LocalDateTime savedAt) {
        // 创建者同时是普通成员，后续成员查询统一通过成员表判断。
        gatheringMemberMapper.insert(newMembership(gatheringId, userId, savedAt));
    }

    /**
     * 构造成员新增候选记录；恢复时由 Mapper 保留原关系 ID 与创建时间。
     * @param gatheringId 已确认有效的同行 ID
     * @param userId 已确认有效的用户 ID
     * @param savedAt 本次写入的 UTC 毫秒时间
     * @return 带新 UUIDv7、创建及修改时间的有效成员候选记录
     */
    private GatheringMember newMembership(UUID gatheringId, UUID userId, LocalDateTime savedAt) {
        // 统一成员初始字段，创建与加入分别选择直接新增或新增/恢复。
        GatheringMember member = new GatheringMember();
        member.setId(UuidCreator.getTimeOrderedEpoch());
        member.setGatheringId(gatheringId);
        member.setUserId(userId);
        member.setCreatedAt(savedAt);
        member.setUpdatedAt(savedAt);
        return member;
    }

    /** 生成固定长度的随机长期邀请码，唯一性由保存时的数据库约束保证。 */
    private String generateInviteCode() {
        // 从统一字符集中逐位选择，面对面短码由后续独立流程处理。
        StringBuilder code = new StringBuilder(INVITE_CODE_LENGTH);
        for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
            code.append(INVITE_CODE_CHARACTERS.charAt(INVITE_CODE_RANDOM.nextInt(INVITE_CODE_CHARACTERS.length())));
        }
        return code.toString();
    }

    /**
     * 将已加载的数据组装为同行响应。
     * @param gathering 已确认有效的同行
     * @param creator 已加载的创建者公开资料
     * @param memberCount 有效成员数量，创建时直接传入 1
     * @param momentCount 有效关联瞬间数量，创建时直接传入 0
     * @return 时间转换为 UTC Instant 的同行信息
     */
    private GatheringResponse toResponse(Gathering gathering, PublicUserResponse creator,
                                         long memberCount, long momentCount) {
        // 数量由具体业务确定，统一转换数据库时间和响应字段。
        return new GatheringResponse(gathering.getId(), gathering.getName(), gathering.getDescription(),
                creator, memberCount, momentCount, gathering.getInviteCode(),
                gathering.getCreatedAt().toInstant(ZoneOffset.UTC),
                gathering.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
}
