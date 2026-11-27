package com.you.youbackend.gathering.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.common.util.InviteCodeGenerator;
import com.you.youbackend.common.util.TextUtils;
import com.you.youbackend.common.util.TimeUtils;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.entity.GatheringMember;
import com.you.youbackend.gathering.event.GatheringDissolvedEvent;
import com.you.youbackend.gathering.event.GatheringMemberLeftEvent;
import com.you.youbackend.gathering.event.GatheringMomentsLinkedEvent;
import com.you.youbackend.gathering.event.GatheringMomentsUnlinkedEvent;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.FaceToFaceRules;
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
import com.you.youbackend.gathering.response.GatheringDetailedResponse;
import com.you.youbackend.gathering.response.GatheringSummaryResponse;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同行创建、成员加入与瞬间关联资格校验。
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class GatheringService {
    /**
     * 列表介绍摘要的最大 Unicode 码点数。
     */
    private static final int SUMMARY_DESCRIPTION_LENGTH = 100;
    private final GatheringMapper gatheringMapper;
    private final GatheringMemberMapper gatheringMemberMapper;
    private final UserService userService;
    private final MomentMapper momentMapper;
    private final ApplicationEventPublisher events;
    private final MomentService momentService; // TODO 循环依赖！

    /**
     * 普通成员主动退出并解除本人全部瞬间关联，两个写操作在同一事务中完成。
     *
     * @param gatheringId 未解散的同行 ID，允许已结束
     * @param userId      当前认证用户 ID，创建者须选择解散
     */
    @Transactional
    public void leaveGathering(UUID gatheringId, UUID userId) {
        // 1. 同行须有效，创建者保留管理责任，通过解散关闭同行。
        Gathering gathering = getGathering(gatheringId);
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
        Instant now = TimeUtils.now();
        gatheringMemberMapper.leaveGathering(member.getId(), now);
        int removed = momentMapper.removeAllOwnedGathering(gatheringId, userId, now);
        // 发布事件：【名成员已退出同行】
        events.publishEvent(new GatheringMemberLeftEvent(gatheringId, userId, now));
        log.info("已写入同行退出，gatheringId={}，userId={}，removedMomentCount={}", gatheringId, userId, removed);
    }

    /**
     * 创建者结束同行并关闭邀请，原有成员与瞬间关联保留。
     *
     * @param gatheringId 有效同行 ID
     * @param userId      当前认证用户 ID
     */
    @Transactional
    public void endGathering(UUID gatheringId, UUID userId) {
        // 1. 校验有效同行和创建者身份，重复结束保持成功。
        Gathering gathering = getGathering(gatheringId);
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
     *
     * @param gatheringId 同行 ID，已解散记录用于识别重复请求
     * @param userId      当前认证用户 ID
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
        Instant now = TimeUtils.now();
        gatheringMapper.dissolveGathering(gatheringId, now);
        int removed = momentMapper.removeAllGathering(gatheringId, now);

        // 发布事件：【一个同行已解散】
        events.publishEvent(new GatheringDissolvedEvent(
                gatheringId,
                now
        ));

        log.info("已写入同行解散，gatheringId={}，userId={}，removedMomentCount={}", gatheringId, userId, removed);
    }

    /**
     * 批量关联本人瞬间，整批校验与写入共同提交或回滚。
     *
     * @param gatheringId 进行中的目标同行 ID
     * @param request     已通过列表及 UUIDv7 校验的请求
     * @param userId      当前认证用户 ID
     */
    @Transactional
    public void addMoments(UUID gatheringId, AddGatheringMomentsRequest request, UUID userId) {
        // 1. 鉴权，验证用户能否给目标 gathering 添加自己的 moment
        validateCanAddMoment(gatheringId, userId);
        // TODO 现在的业务规则是忽略掉已经关联过的
        // 2. 找到待关联的 momentId 列表
        List<Moment> moments = momentService.getOwnedMoments(request.momentIds(), userId);
        List<UUID> pendingIds = moments.stream().filter(moment -> moment.getGatheringId() == null)
                .map(Moment::getId).toList();
        if (pendingIds.isEmpty()) {
            log.debug("瞬间已全部关联当前同行，gatheringId={}，userId={}，count={}", gatheringId, userId, moments.size());
            return;
        }
        // 3. 写入关联
        Instant updatedAt = TimeUtils.now();
        momentMapper.addToGatheringByUserIdAndGatheringId(userId, gatheringId, pendingIds, updatedAt);
        // 4. 发布事件
        events.publishEvent(new GatheringMomentsLinkedEvent(
                gatheringId, userId, pendingIds, updatedAt
        ));
        log.info("已写入瞬间批量关联，gatheringId={}，userId={}，count={}", gatheringId, userId, moments.size());
    }

    /**
     * 批量取消本人瞬间在指定同行中的关联，允许已结束同行，重复取消成功。
     *
     * @param gatheringId 当前操作的同行 ID
     * @param request     已通过列表及 UUIDv7 校验的请求
     * @param userId      当前认证用户 ID
     */
    @Transactional
    public void removeMoments(UUID gatheringId, RemoveGatheringMomentsRequest request, UUID userId) {
        // 1. 鉴权，验证用户能否给目标 gathering 取消关联自己的 moment
        getMemberGathering(gatheringId, userId);
        // 2. 找到待取消关联的列表，仅取消当前 gathering 的，其他的忽略
        List<Moment> moments = momentService.getOwnedMoments(request.momentIds(), userId);
        List<UUID> pendingIds = moments.stream().filter(moment -> gatheringId.equals(moment.getGatheringId()))
                .map(Moment::getId).toList();
        if (pendingIds.isEmpty()) {
            log.debug("指定瞬间已移出同行，gatheringId={}，userId={}，count={}", gatheringId, userId, moments.size());
            return;
        }
        // 3. 清空符合条件的关联，写入库
        Instant updatedAt = TimeUtils.now();
        momentMapper.removeFromGatheringByUserIdAndGatheringId(userId, gatheringId, pendingIds, updatedAt);
        // 4. 发布事件：【一个或多个瞬间已从同行中移出】
        events.publishEvent(new GatheringMomentsUnlinkedEvent(
                gatheringId, pendingIds, updatedAt
        ));
        log.info("已写入瞬间批量取消关联，gatheringId={}，userId={}，count={}", gatheringId, userId, pendingIds.size());
    }

    /**
     * 浏览本人已加入的有效同行，分页后批量补齐数量。
     *
     * @param request 已由 PageQuery 校验的分页参数
     * @param userId  当前认证用户 ID
     * @return 按创建时间、ID 倒序的同行摘要页
     */
    @Transactional(readOnly = true)
    public ResultPage<GatheringSummaryResponse, GatheringListCursor> browseGatherings(
            PageRequest<GatheringListCursor> request, UUID userId) {
        // 1. 页面查询
        GatheringListCursor cursor = request.cursor();
        Instant cursorTime = cursor == null ? null : cursor.createdAt();
        UUID cursorId = cursor == null ? null : cursor.id();
        List<Gathering> rows = gatheringMapper.selectPageAfterCursorByUserId(userId, cursorTime, cursorId, request.limit() + 1);
        // 2. 空集直接返回
        if (rows.isEmpty()) {
            return ResultPage.empty();
        }
        // 3. 构造响应
        List<Gathering> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        List<GatheringSummaryResponse> gatheringSummaryResponses = convertToSummaryResponses(page);
        boolean hasMore = rows.size() > request.limit();
        log.debug("浏览本人同行，userId={}，count={}，hasMore={}", userId, gatheringSummaryResponses.size(), hasMore);
        return ResultPage.toResultPage(
                gatheringSummaryResponses,
                hasMore,
                last -> new GatheringListCursor(last.createdAt(), last.id()));
    }


    /**
     * 有效成员读取同行详情，分页内容从各自入口读取。
     *
     * @param gatheringId 同行 ID
     * @param userId      当前认证用户 ID
     * @return 同行信息、实际数量与创建者公开资料
     */
    @Transactional(readOnly = true)
    public GatheringDetailedResponse getGatheringInfo(UUID gatheringId, UUID userId) {
        // 1. 获取目标 gathering 并鉴权
        Gathering gathering = getMemberGathering(gatheringId, userId);
        log.debug("查看同行信息，gatheringId={}，userId={}", gatheringId, userId);
        return convertToDetailedResponse(gathering);
    }

    /**
     * 创建者全量修改基本信息，保存及响应加载在同一事务完成。
     *
     * @param gatheringId 同行 ID
     * @param request     已校验名称、介绍，null 介绍表示清空
     * @param userId      当前认证用户 ID
     * @return 最新同行信息
     */
    @Transactional
    public GatheringDetailedResponse updateGathering(UUID gatheringId, UpdateGatheringRequest request, UUID userId) {
        // 1. 获取 gathering 并且鉴权
        Gathering gathering = getCreatorGathering(gatheringId, userId);
        // 2. 保存允许编辑的字段，null 会清空数据库原值。
        gathering.setName(request.name());
        gathering.setDescription(request.description());
        gathering.setUpdatedAt(TimeUtils.now());
        gatheringMapper.updateContentById(gathering);
        // 3. 构造响应
        GatheringDetailedResponse response = convertToDetailedResponse(gathering);
        log.info("已写入同行基本信息修改，gatheringId={}，userId={}", gatheringId, userId);
        return response;
    }

    /**
     * 有效成员分页读取其他有效成员的公开资料。
     *
     * @param gatheringId 同行 ID
     * @param request     已校验的成员关系创建时间、用户 ID 游标
     * @param userId      当前认证用户 ID
     * @return 按成员关系创建时间、用户 ID 正序的公开资料页
     */
    @Transactional(readOnly = true)
    public ResultPage<PublicUserResponse, GatheringMemberCursor> browseMembers(
            UUID gatheringId, PageRequest<GatheringMemberCursor> request, UUID userId) {
        // 1. 校验同行权限
        getMemberGathering(gatheringId, userId);
        // 2. 页面查询
        GatheringMemberCursor cursor = request.cursor();
        Instant cursorTime = cursor == null ? null : cursor.createdAt();
        UUID cursorUserId = cursor == null ? null : cursor.userId();
        List<GatheringMember> rows = gatheringMemberMapper.selectPageAfterCursorById(gatheringId, cursorTime, cursorUserId, request.limit() + 1);
        // 2.1 空集直接返回
        if (rows.isEmpty()) {
            return ResultPage.empty();
        }
        // 3. 获取用户资料
        List<GatheringMember> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        List<UUID> userIds = page.stream().map(GatheringMember::getId).toList();
        Map<UUID, PublicUserResponse> publicUserResponsesById = userService.getPublicUsers(userIds);
        // 4. 按照顺序构造响应
        List<PublicUserResponse> publicUserResponses = userIds.stream().map(publicUserResponsesById::get).toList();
        boolean hasMore = rows.size() > request.limit();
        GatheringMemberCursor nextCursor = null;
        if (hasMore) {
            GatheringMember last = page.get(page.size() - 1);
            nextCursor = new GatheringMemberCursor(last.getCreatedAt(), last.getUserId());
        }
        return ResultPage.toResultPage(publicUserResponses, hasMore, nextCursor);
    }

    /**
     * 创建同行与创建者成员关系，两类记录共同提交或回滚。
     *
     * @param request 已通过名称及介绍校验的请求
     * @param userId  当前登录用户 ID，由统一认证过滤器提供
     * @return 初始成员数为 1、瞬间数为 0 的同行信息，包含长期邀请码
     */
    @Transactional
    public GatheringDetailedResponse createGathering(CreateGatheringRequest request, UUID userId) {
        // 1. 创建一个新的 gathering 入库
        Instant savedAt = TimeUtils.now();
        Gathering gathering = saveNewGathering(request, userId, savedAt);
        // 2. 写入创建者的关系
        saveGatheringMembership(gathering.getId(), userId, savedAt);
        // 3. 组装响应返回
        GatheringDetailedResponse response = convertToDetailedResponse(gathering);
        log.info("已写入同行及创建者成员关系，gatheringId={}，userId={}", gathering.getId(), userId);
        return response;
    }

    private void saveGatheringMembership(UUID gatheringId, UUID userId, Instant savedAt) {
        GatheringMember member = new GatheringMember();
        member.setId(UuidCreator.getTimeOrderedEpoch());
        member.setGatheringId(gatheringId);
        member.setUserId(userId);
        member.setCreatedAt(savedAt);
        member.setUpdatedAt(savedAt);
        gatheringMemberMapper.insertOrRestore(member);
    }

    private GatheringDetailedResponse convertToDetailedResponse(
            Gathering gathering,
            PublicUserResponse creator,
            long memberCount,
            long momentCount) {
        return GatheringDetailedResponse.builder()
                .status(gathering.getStatus())
                .id(gathering.getId())
                .name(gathering.getName())
                .description(gathering.getDescription())
                .creator(creator)
                .memberCount(memberCount)
                .momentCount(momentCount)
                .inviteCode(gathering.getInviteCode())
                .createdAt(gathering.getCreatedAt())
                .updatedAt(gathering.getUpdatedAt())
                .build();
    }

    private GatheringDetailedResponse convertToDetailedResponse(Gathering gathering) {
        PublicUserResponse creator = userService.getPublicUser(gathering.getCreatorUserId());
        long memberCount = gatheringMemberMapper.CountByGatheringId(gathering.getId());
        long momentCount = momentMapper.countByGatheringId(gathering.getId()); // TODO 这里直接持有别的mapper好吗？
        return convertToDetailedResponse(gathering, creator, memberCount, momentCount);
    }

    private GatheringSummaryResponse convertToSummaryResponse(
            Gathering gathering,
            PublicUserResponse creatorInfo,
            long memberCount,
            long momentCount) {
        String description = TextUtils.truncateByCodePoints(gathering.getDescription(), 100); // TODO 这里不要写死 100 在代码里
        return GatheringSummaryResponse.builder()
                .status(gathering.getStatus())
                .id(gathering.getId())
                .name(gathering.getName())
                .descriptionPreview(description)
                .creator(creatorInfo)
                .memberCount(memberCount)
                .momentCount(momentCount)
                .createdAt(gathering.getCreatedAt())
                .build();
    }


    private List<GatheringSummaryResponse> convertToSummaryResponses(List<Gathering> gatherings) {
        Assert.notEmpty(gatherings, "至少传入一个 gatherings");
        // 1. 获取创建者的资料
        List<UUID> creatorIds = gatherings.stream().map(Gathering::getCreatorUserId).distinct().toList();
        Map<UUID, PublicUserResponse> publicUsersByUserId = userService.getPublicUsers(creatorIds);
        // 2. 获取同行的成员数量
        List<UUID> gatheringIds = gatherings.stream().map(Gathering::getId).toList();
        Map<UUID, Long> memberCountsByGatheringId = gatheringMemberMapper.countByGatheringIds(gatheringIds);
        // 3. 获取同行的moment数量
        Map<UUID, Long> momentCountsGatheringId = momentMapper.countByGatheringIds(gatheringIds);
        // 4. 构造响应
        return gatherings.stream().map(
                gathering -> convertToSummaryResponse(
                        gathering,
                        publicUsersByUserId.get(gathering.getCreatorUserId()),
                        memberCountsByGatheringId.get(gathering.getId()),
                        momentCountsGatheringId.get(gathering.getId())
                )
        ).toList();
    }

    /**
     * 构造成员新增候选记录；恢复时由 Mapper 保留原关系 ID 与创建时间。
     *
     * @param gatheringId 已确认有效的同行 ID
     * @param userId      已确认有效的用户 ID
     * @param savedAt     本次写入的 UTC 毫秒时间
     * @return 带新 UUIDv7、创建及修改时间的有效成员候选记录
     */
    private GatheringMember newMembership(UUID gatheringId, UUID userId, Instant savedAt) {
        // 统一成员初始字段，创建与加入分别选择直接新增或新增/恢复。
        GatheringMember member = new GatheringMember();
        member.setId(UuidCreator.getTimeOrderedEpoch());
        member.setGatheringId(gatheringId);
        member.setUserId(userId);
        member.setCreatedAt(savedAt);
        member.setUpdatedAt(savedAt);
        return member;
    }

    /**
     * 根据长期邀请码预览加入目标，整个流程只读取数据。
     *
     * @param request 已通过邀请码校验的查询参数
     * @param userId  当前登录用户 ID
     * @return 目标基本信息、创建者公开资料和当前有效成员标记
     * @throws BusinessException 邀请码不存在、同行已解散或邀请关闭时返回 404
     */
    @Transactional(readOnly = true)
    public GatheringJoinPreviewResponse previewJoin(GatheringJoinPreviewRequest request, UUID userId) {
        // 1. 获取目标 gathering 并验证能否加入
        String inviteCode = request.inviteCode();
        Gathering gathering = getJoinableGathering(inviteCode);
        // 2. 构造加入的响应
        boolean isMember = gatheringMemberMapper.existsByGatheringIdAndUserId(gathering.getId(), userId);
        PublicUserResponse creator = userService.getPublicUser(gathering.getCreatorUserId());
        log.debug("预览同行加入，gatheringId={}，userId={}，isMember={}", gathering.getId(), userId, isMember);
        return GatheringJoinPreviewResponse.builder()
                .id(gathering.getId())
                .name(gathering.getName())
                .description(gathering.getDescription())
                .creator(creator)
                .isMember(isMember)
                .build();
    }

    /**
     * 确认加入同行：按提交时状态校验，建立或恢复成员关系后返回最新同行信息。
     *
     * @param request 已通过邀请码校验的请求，支持直接调用
     * @param userId  当前登录用户 ID，由统一认证过滤器提供
     * @return 当前同行信息和实际成员、瞬间数量；已是有效成员时返回现有结果
     * @throws BusinessException 邀请码不存在、同行已解散或邀请关闭时返回 404
     */
    @Transactional
    public GatheringDetailedResponse joinGathering(JoinGatheringRequest request, UUID userId) {
        // 1. 获取目标 gathering 并验证能否加入
        String inviteCode = request.inviteCode();
        Gathering gathering = getJoinableGathering(inviteCode);
        // 2. 写入成员关系
        Instant savedAt = TimeUtils.now();
        saveGatheringMembership(gathering.getId(), userId, savedAt);
        // 3. 构建响应
        return convertToDetailedResponse(gathering);
    }

    /**
     * 凭同行 ID 和短码确认加入，全部校验与成员写入在一个数据库事务内完成。
     * <p>流程：查询并校验目标 → 新增、恢复或沿用成员关系 → 组装当前同行信息。
     * 附近预览只帮助发现目标，本方法直接根据数据库短码、邀请开关和创建时间判断加入资格。
     *
     * @param request 已校验的同行 ID 与四位短码
     * @param userId  统一认证过滤器提供的用户 ID
     * @return 加入后的同行信息，重复加入保持原关系
     */
    @Transactional
    public GatheringDetailedResponse joinFromFaceToFace(JoinFaceToFaceRequest request, UUID userId) {
        String inviteCode = request.faceCode();
        // 1. 一次查询校验目标，确认短码一致且邀请仍在有效时间内。
        Gathering gathering = requireFaceToFaceGathering(request.gatheringId(), request.faceCode());
        // 2. 复用成员新增与恢复规则，已有有效成员关系保持原样。
        ensureMembership(gathering.getId(), userId);
        // 3. 使用已加载的同行组装响应，成员写入与响应加载共同成功或回滚。
        return convertToDetailedResponse(gathering);
    }

    /**
     * 校验用户能否向指定同行关联本人瞬间，由发布等业务在写入前调用。
     *
     * @param gatheringId 请求中已提供的同行 ID
     * @param userId      当前登录用户 ID
     * @throws BusinessException 同行不存在或已解散返回 404，已结束返回 400，非有效成员返回 403
     */
    public void validateCanAddMoment(UUID gatheringId, UUID userId) {
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
        if (!gatheringMemberMapper.existsByGatheringIdAndUserId(gatheringId, userId)) {
            log.warn("关联瞬间被拒绝，gatheringId={}，userId={}，reason=非有效成员", gatheringId, userId);
            throw BusinessException.forbidden("只有同行有效成员可以添加瞬间");
        }
    }

    /**
     * 校验已查询同行的创建者身份，供结束、解散操作使用。
     */
    private void requireCreator(Gathering gathering, UUID userId) {
        // 创建者身份来自同行主记录，其他成员无管理权限。
        if (!userId.equals(gathering.getCreatorUserId())) {
            log.warn("同行管理被拒绝，gatheringId={}，userId={}，reason=非创建者", gathering.getId(), userId);
            throw BusinessException.forbidden("仅创建者可以操作");
        }
    }

    /**
     * 从数据库校验面对面加入资格。
     *
     * @param gatheringId 用户选择的同行 ID
     * @param faceCode    用户提交的四位短码
     * @return 短码匹配、邀请开启且创建未满 10 分钟的同行
     */
    private Gathering requireFaceToFaceGathering(UUID gatheringId, String faceCode) {
        // 同行软删除由 Mapper 过滤，短码永久保存但仅在创建后 10 分钟内可用于加入。
        Gathering gathering = gatheringMapper.selectById(gatheringId);
        Instant now = Instant.now();
        if (gathering == null || !Boolean.TRUE.equals(gathering.getInviteEnabled())
                || !faceCode.equals(gathering.getFaceCode())
                || !gathering.getCreatedAt().plus(FaceToFaceRules.WINDOW).isAfter(now)) {
            log.debug("面对面加入校验失败，gatheringId={}，reason=同行失效、邀请关闭、短码不匹配或已过期", gatheringId);
            throw BusinessException.notFound("FACE_TO_FACE_INVITATION_UNAVAILABLE", "同行不可加入、短码不匹配或邀请已过期");
        }
        return gathering;
    }

    // TODO 权限不足的错误信息不对！不能特调！

    public Gathering getCreatorGathering(UUID gatheringId, UUID userId) {
        Gathering gathering = getGathering(gatheringId);
        if (!gathering.getCreatorUserId().equals(userId)) {
            log.warn("访问同行被拒绝，gatheringId={}，userId={}，reason=不是创建者", gatheringId, userId);
            throw BusinessException.forbidden("只有同行创建者可以查看");
        }
        return gathering;
    }

    /**
     * 读取有效同行并校验当前用户成员资格，供同行和内容读取入口共用。
     *
     * @param gatheringId 同行 ID
     * @param userId      当前认证用户 ID
     * @return 已确认有效且本人为有效成员的同行
     */
    public Gathering getMemberGathering(UUID gatheringId, UUID userId) {
        // 先确认同行有效，再确认当前关系，邀请开关及结束状态均不影响已加入成员查看。
        Gathering gathering = getGathering(gatheringId);
        if (!gatheringMemberMapper.existsByGatheringIdAndUserId(gatheringId, userId)) {
            log.warn("访问同行被拒绝，gatheringId={}，userId={}，reason=非有效成员", gatheringId, userId);
            throw BusinessException.forbidden("只有同行有效成员可以查看");
        }
        return gathering;
    }

    /**
     * 根据邀请码加载开放邀请的同行；结束操作会关闭邀请。
     *
     * @param inviteCode 已通过边界校验的长期邀请码
     * @return 未解散且开放邀请的完整同行实体
     * @throws BusinessException 目标不可加入时统一返回 404
     */
    private Gathering getJoinableGathering(String inviteCode) {
        // 邀请可用性取决于同行仍有效且邀请开启，软删除由 Mapper 过滤。
        Gathering gathering = gatheringMapper.selectByInviteCode(inviteCode);
        if (gathering == null || !Boolean.TRUE.equals(gathering.getInviteEnabled())) {
            throw BusinessException.notFound("GATHERING_INVITATION_UNAVAILABLE", "邀请码无效、邀请已关闭或同行已解散");
        }
        return gathering;
    }

    /**
     * 读取有效同行，软删除由全局配置过滤，缺失返回 404。
     */
    private Gathering getGathering(UUID gatheringId) {
        // 同行不存在和已解散使用统一业务语义。
        Gathering gathering = gatheringMapper.selectById(gatheringId);
        if (gathering == null) {
            throw BusinessException.notFound("GATHERING_NOT_FOUND", "同行不存在或已解散");
        }
        return gathering;
    }

    /**
     * 确保用户拥有有效成员关系，须在加入业务事务内调用。
     *
     * @param gatheringId 已校验可加入的同行 ID
     * @param userId      统一认证过滤器提供的当前用户 ID
     */
    private void ensureMembership(UUID gatheringId, UUID userId) {
        // 1. 普通重复请求直接使用现有关系，审计时间保持原样。
        if (gatheringMemberMapper.existsByGatheringIdAndUserId(gatheringId, userId)) {
            log.debug("用户已在同行中，跳过重复加入，gatheringId={}，userId={}", gatheringId, userId);
            return;
        }
        // 2. 使用唯一索引完成新增或恢复，重叠请求也只保留一条关系。
        Instant savedAt = TimeUtils.now();
        gatheringMemberMapper.insertOrRestore(newMembership(gatheringId, userId, savedAt));
        log.info("已写入同行成员加入，gatheringId={}，userId={}", gatheringId, userId);
    }

    /**
     * 保存进行中且开放邀请的同行。
     *
     * @param request 已校验并整理名称的请求
     * @param userId  已确认有效的创建者 ID
     * @param savedAt 本次写入统一使用的 UTC 毫秒时间
     * @return 已保存的同行，邀请码冲突时抛出业务异常供客户端重试
     */
    private Gathering saveNewGathering(CreateGatheringRequest request, UUID userId, Instant savedAt) {
        // 1. 身份、UUIDv7、初始状态和长期邀请码均由服务端确定。
        Gathering gathering = new Gathering();
        gathering.setId(UuidCreator.getTimeOrderedEpoch());
        gathering.setCreatorUserId(userId);
        gathering.setName(request.name());
        gathering.setDescription(request.description());
        gathering.setStatus(GatheringStatus.ONGOING);
        gathering.setInviteEnabled(true);
        gathering.setInviteCode(InviteCodeGenerator.generate());
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

}
