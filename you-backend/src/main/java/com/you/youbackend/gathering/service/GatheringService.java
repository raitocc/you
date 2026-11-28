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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同行的创建、加入、退出、结束与解散，成员与瞬间关联管理及列表分页。
 * 资格校验与已鉴权实体读取由 GatheringAccessService 承担。
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
    private final MomentService momentService;
    private final GatheringAccessService gatheringAccessService;

    /**
     * 普通成员主动退出并解除本人全部瞬间关联，两个写操作在同一事务中完成。
     *
     * <p>流程：成员资格与创建者校验 → 软删成员关系 → 清空本人在同行中的瞬间关联 → 发布退出事件。
     * 副作用：gathering_member、moment 同事务写入，失败整体回滚。</p>
     *
     * @param gatheringId 未解散的同行 ID，允许已结束
     * @param userId      当前认证用户 ID，创建者须选择解散
     * @throws BusinessException 非有效成员返回 403，创建者退出返回 400，同行不存在或已解散返回 404
     */
    @Transactional
    public void leaveGathering(UUID gatheringId, UUID userId) {
        // TODO 重复退出不幂等：已退出再退出报 403；恢复幂等要一个类似 selectIncludingDeleted 的成员查询，不重要先放着
        // 1. 加载同行并校验本人是有效成员；非成员（含已退出）拦 403
        Gathering gathering = gatheringAccessService.getMemberGathering(gatheringId, userId);
        // 2. 检查创建者：创建者不能退出，必须走解散
        if (userId.equals(gathering.getCreatorUserId())) {
            log.warn("同行退出被拒绝，gatheringId={}，userId={}，reason=创建者须解散同行", gatheringId, userId);
            throw BusinessException.badRequest("GATHERING_CREATOR_CANNOT_LEAVE", "创建者无法直接退出，请选择解散同行");
        }
        // 3. 软删成员关系；重新加入会恢复这条记录
        Instant now = TimeUtils.now();
        gatheringMemberMapper.deleteByGatheringIdAndUserId(gatheringId, userId, now);
        // 4. 清空本人在该同行的瞬间归属，瞬间内容保留
        int removed = momentMapper.removeGatheringIdByGatheringIdAndUserId(gatheringId, userId, now);
        // 发布事件：【一名成员已退出同行】
        events.publishEvent(new GatheringMemberLeftEvent(gatheringId, userId, now));
        log.info("已写入同行退出，gatheringId={}，userId={}，removedMomentCount={}", gatheringId, userId, removed);
    }

    /**
     * 创建者结束同行并关闭邀请，原有成员与瞬间关联保留。
     *
     * <p>流程：创建者校验 → 已结束直接返回（幂等）→ 写入结束状态并关闭邀请。
     * 副作用：gathering 单表更新，不触碰成员和瞬间关联。</p>
     *
     * @param gatheringId 有效同行 ID
     * @param userId      当前认证用户 ID
     * @throws BusinessException 同行不存在或已解散返回 404，非创建者返回 403
     */
    @Transactional
    public void endGathering(UUID gatheringId, UUID userId) {
        // 1. 加载同行并校验创建者身份；非创建者拦 403
        Gathering gathering = gatheringAccessService.getCreatorGathering(gatheringId, userId);
        if (gathering.getStatus() == GatheringStatus.ENDED) {
            log.debug("同行已结束，gatheringId={}，userId={}", gatheringId, userId);
            return;
        }
        // 2. 写入结束状态并关闭加入入口；成员和瞬间关联不动。重复结束在上面直接返回成功
        Instant now = TimeUtils.now();
        gatheringMapper.endGatheringByGatheringId(gatheringId, now);
        log.info("已写入同行结束，gatheringId={}，userId={}", gatheringId, userId);
    }

    /**
     * 创建者解散同行并解除全部瞬间关联，同一事务提交，成员记录和原始瞬间保留。
     * 不幂等：已解散的同行再次解散返回 404，不做静默成功。
     *
     * <p>流程：创建者校验 → 软删同行 → 清空该同行全部瞬间关联 → 发布解散事件。
     * 副作用：gathering、moment 同事务写入，失败整体回滚。</p>
     *
     * @param gatheringId 同行 ID
     * @param userId      当前认证用户 ID
     * @throws BusinessException 同行不存在或已解散返回 404，非创建者返回 403
     */
    @Transactional
    public void dissolveGathering(UUID gatheringId, UUID userId) {
        // TODO 解散不幂等：已解散再解散返回 404；要不要恢复成静默成功（参考 album/moment 删除）待定
        // 1. 加载同行并校验创建者身份；非创建者拦 403
        gatheringAccessService.getCreatorGathering(gatheringId, userId);
        // 2. 软删同行主记录，解散时间记入 deleted_at
        Instant now = TimeUtils.now();
        gatheringMapper.deleteByGatheringId(gatheringId, now);
        // 3. 清空该同行全部瞬间的归属，瞬间内容保留
        int removed = momentMapper.removeGatheringIdByGatheringId(gatheringId, now);
        // 4. 发布解散事件，收件箱等下游各自清理自己的记录
        events.publishEvent(new GatheringDissolvedEvent(
                gatheringId,
                now
        ));
        log.info("已写入同行解散，gatheringId={}，userId={}，removedMomentCount={}", gatheringId, userId, removed);
    }

    /**
     * 批量关联本人瞬间，整批校验与写入共同提交或回滚；已关联其他同行的瞬间忽略。
     *
     * <p>流程：关联资格校验 → 校验瞬间均为本人有效 → 过滤未关联的瞬间 → 批量写入同行归属 → 发布关联事件。
     * 副作用：moment 同事务更新，失败整体回滚。</p>
     *
     * @param gatheringId 进行中的目标同行 ID
     * @param request     已通过列表及 UUIDv7 校验的请求
     * @param userId      当前认证用户 ID
     * @throws BusinessException 同行不存在、已解散或已结束、非有效成员（404/400/403），
     *                          或任一瞬间不存在、已删除、不属于本人（400）
     */
    @Transactional
    public void addMoments(UUID gatheringId, AddGatheringMomentsRequest request, UUID userId) {
        // 1. 校验添加资格：加载同行、确认还在进行中、确认本人是有效成员
        gatheringAccessService.validateCanAddMoment(gatheringId, userId);
        // TODO 现在的业务规则是忽略掉已经关联过的
        // 2. 查出本人的这批瞬间，只挑还没有归属同行的；已关联过的跳过
        List<Moment> moments = momentService.getOwnedMoments(request.momentIds(), userId);
        List<UUID> pendingIds = moments.stream().filter(moment -> moment.getGatheringId() == null)
                .map(Moment::getId).toList();
        if (pendingIds.isEmpty()) {
            log.debug("瞬间已全部关联当前同行，gatheringId={}，userId={}，count={}", gatheringId, userId, moments.size());
            return;
        }
        // 3. 把这批瞬间的归属写成目标同行
        Instant updatedAt = TimeUtils.now();
        momentMapper.addToGatheringByUserIdAndGatheringId(userId, gatheringId, pendingIds, updatedAt);
        // 4. 发布关联事件，收件箱等下游各自写入
        events.publishEvent(new GatheringMomentsLinkedEvent(
                gatheringId, userId, pendingIds, updatedAt
        ));
        log.info("已写入瞬间批量关联，gatheringId={}，userId={}，count={}", gatheringId, userId, pendingIds.size());
    }

    /**
     * 批量取消本人瞬间在指定同行中的关联，允许已结束同行，重复取消成功。
     *
     * <p>流程：成员资格校验 → 校验瞬间均为本人有效 → 只保留当前同行的关联 → 批量清空归属 → 发布移出事件。
     * 副作用：moment 同事务更新，失败整体回滚。</p>
     *
     * @param gatheringId 当前操作的同行 ID
     * @param request     已通过列表及 UUIDv7 校验的请求
     * @param userId      当前认证用户 ID
     * @throws BusinessException 同行不存在、已解散或非有效成员（404/403），
     *                          或任一瞬间不存在、已删除、不属于本人（400）
     */
    @Transactional
    public void removeMoments(UUID gatheringId, RemoveGatheringMomentsRequest request, UUID userId) {
        // 1. 加载同行并校验本人是有效成员；已结束的同行也允许取消关联
        gatheringAccessService.getMemberGathering(gatheringId, userId);
        // 2. 查出本人的这批瞬间，只挑归属就是当前同行的；归属其他同行的不动
        List<Moment> moments = momentService.getOwnedMoments(request.momentIds(), userId);
        List<UUID> pendingIds = moments.stream().filter(moment -> gatheringId.equals(moment.getGatheringId()))
                .map(Moment::getId).toList();
        if (pendingIds.isEmpty()) {
            log.debug("指定瞬间已移出同行，gatheringId={}，userId={}，count={}", gatheringId, userId, moments.size());
            return;
        }
        // 3. 清空这批瞬间的同行归属，瞬间内容保留
        Instant updatedAt = TimeUtils.now();
        momentMapper.removeFromGatheringByUserIdAndGatheringId(userId, gatheringId, pendingIds, updatedAt);
        // 4. 发布移出事件，收件箱等下游各自失效
        events.publishEvent(new GatheringMomentsUnlinkedEvent(
                gatheringId, pendingIds, updatedAt
        ));
        log.info("已写入瞬间批量取消关联，gatheringId={}，userId={}，count={}", gatheringId, userId, pendingIds.size());
    }

    /**
     * 浏览本人已加入的有效同行，分页后批量补齐数量。
     *
     * <p>流程：按游标分页查询本人有效同行 → 批量补齐创建者资料、成员数和瞬间数 → 组装摘要页。
     * 只读查询，无副作用。</p>
     *
     * @param request 已由 PageQuery 校验的分页参数
     * @param userId  当前认证用户 ID
     * @return 按创建时间、ID 倒序的同行摘要页
     */
    @Transactional(readOnly = true)
    public ResultPage<GatheringSummaryResponse, GatheringListCursor> browseGatherings(
            PageRequest<GatheringListCursor> request, UUID userId) {
        // 1. 按游标查本人加入且未解散的同行，按创建时间、ID 倒序，多取一条判断下一页
        GatheringListCursor cursor = request.cursor();
        Instant cursorTime = cursor == null ? null : cursor.createdAt();
        UUID cursorId = cursor == null ? null : cursor.id();
        List<Gathering> rows = gatheringMapper.selectPageAfterCursorByUserId(userId, cursorTime, cursorId, request.limit() + 1);
        // 2. 空集直接返回
        if (rows.isEmpty()) {
            return ResultPage.empty();
        }
        // 3. 截出本页，批量补齐创建者资料、成员数和瞬间数组装摘要
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
     * <p>流程：成员资格校验 → 补齐创建者资料、成员数和瞬间数 → 组装详情。
     * 只读查询，无副作用。</p>
     *
     * @param gatheringId 同行 ID
     * @param userId      当前认证用户 ID
     * @return 同行信息、实际数量与创建者公开资料
     * @throws BusinessException 同行不存在或已解散返回 404，非有效成员返回 403
     */
    @Transactional(readOnly = true)
    public GatheringDetailedResponse getGatheringInfo(UUID gatheringId, UUID userId) {
        // 校验本人是有效成员，然后补齐创建者资料和数量，组装详情返回
        Gathering gathering = gatheringAccessService.getMemberGathering(gatheringId, userId);
        log.debug("查看同行信息，gatheringId={}，userId={}", gatheringId, userId);
        return convertToDetailedResponse(gathering);
    }

    /**
     * 创建者全量修改基本信息，保存及响应加载在同一事务完成。
     *
     * <p>流程：创建者校验 → 覆盖名称、介绍并刷新修改时间 → 组装最新详情。
     * 副作用：gathering 单表更新，介绍传 null 显式清空。</p>
     *
     * @param gatheringId 同行 ID
     * @param request     已校验名称、介绍，null 介绍表示清空
     * @param userId      当前认证用户 ID
     * @return 最新同行信息
     * @throws BusinessException 同行不存在或已解散返回 404，非创建者返回 403
     */
    @Transactional
    public GatheringDetailedResponse updateGathering(UUID gatheringId, UpdateGatheringRequest request, UUID userId) {
        // 1. 加载同行并校验创建者身份；非创建者拦 403
        Gathering gathering = gatheringAccessService.getCreatorGathering(gatheringId, userId);
        // 2. 覆盖名称和介绍写库；介绍传 null 会把库里的介绍清掉，名称在请求侧已去首尾空白
        gathering.setName(request.name());
        gathering.setDescription(request.description());
        gathering.setUpdatedAt(TimeUtils.now());
        gatheringMapper.updateContentById(gathering);
        // 3. 用写入后的实体组装最新详情返回
        GatheringDetailedResponse response = convertToDetailedResponse(gathering);
        log.info("已写入同行基本信息修改，gatheringId={}，userId={}", gatheringId, userId);
        return response;
    }

    /**
     * 有效成员分页读取同行成员的公开资料，列表包含本人。
     *
     * <p>流程：成员资格校验 → 按关系创建时间、用户 ID 游标分页 → 批量补齐用户资料 → 组装资料页。
     * 只读查询，无副作用。</p>
     *
     * @param gatheringId 同行 ID
     * @param request     已校验的成员关系创建时间、用户 ID 游标
     * @param userId      当前认证用户 ID
     * @return 按成员关系创建时间、用户 ID 正序的公开资料页
     * @throws BusinessException 同行不存在或已解散返回 404，非有效成员返回 403
     */
    @Transactional(readOnly = true)
    public ResultPage<PublicUserResponse, GatheringMemberCursor> browseMembers(
            UUID gatheringId, PageRequest<GatheringMemberCursor> request, UUID userId) {
        // 1. 加载同行并校验本人是有效成员
        gatheringAccessService.getMemberGathering(gatheringId, userId);
        // 2. 按关系创建时间、用户 ID 游标查成员关系，正序，多取一条判断下一页
        GatheringMemberCursor cursor = request.cursor();
        Instant cursorTime = cursor == null ? null : cursor.createdAt();
        UUID cursorUserId = cursor == null ? null : cursor.userId();
        List<GatheringMember> rows = gatheringMemberMapper.selectPageAfterCursorById(gatheringId, cursorTime, cursorUserId, request.limit() + 1);
        // 2.1 空集直接返回
        if (rows.isEmpty()) {
            return ResultPage.empty();
        }
        // 3. 按本页成员批量查用户公开资料
        List<GatheringMember> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        List<UUID> userIds = page.stream().map(GatheringMember::getUserId).toList();
        Map<UUID, PublicUserResponse> publicUserResponsesById = userService.getPublicUsers(userIds);
        // 4. 保持成员顺序组装资料页，下一页游标取本页末条
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
     * <p>流程：生成同行记录并入库 → 写入创建者成员关系 → 组装详情。
     * 副作用：gathering、gathering_member 同事务写入；邀请码唯一键冲突整批回滚并报 409。</p>
     *
     * @param request 已通过名称及介绍校验的请求
     * @param userId  当前登录用户 ID，由统一认证过滤器提供
     * @return 初始成员数为 1、瞬间数为 0 的同行信息，包含长期邀请码
     * @throws BusinessException 邀请码生成冲突时返回 409，可重新创建
     */
    @Transactional
    public GatheringDetailedResponse createGathering(CreateGatheringRequest request, UUID userId) {
        // 1. 生成同行主记录入库：UUIDv7 和邀请码由服务端定，初始状态进行中、邀请开启
        Instant savedAt = TimeUtils.now();
        Gathering gathering = saveNewGathering(request, userId, savedAt);
        // 2. 把创建者写成第一个有效成员
        ensureMembership(gathering.getId(), userId);
        // 3. 组装详情返回
        GatheringDetailedResponse response = convertToDetailedResponse(gathering);
        log.info("已写入同行及创建者成员关系，gatheringId={}，userId={}", gathering.getId(), userId);
        return response;
    }

    /**
     * 把已加载的同行、创建者资料和数量拼成详情响应，不查库。
     *
     * @param gathering    同行实体，用到状态、ID、名称、介绍、邀请码及创建修改时间
     * @param creator      创建者公开资料，查不到时传 null，响应里保留 null
     * @param memberCount  有效成员数，由调用方统计后传入
     * @param momentCount  有效关联瞬间数，由调用方统计后传入
     * @return 详情响应
     */
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

    /**
     * 查库补齐创建者资料、成员数和瞬间数后拼成详情响应。
     *
     * <p>流程：查创建者公开资料 → 统计有效成员数 → 统计有效关联瞬间数 → 委托四参重载组装。
     * 只读查询，无副作用。</p>
     *
     * @param gathering 有效同行实体
     * @return 含实际数量与创建者资料的详情响应
     */
    private GatheringDetailedResponse convertToDetailedResponse(Gathering gathering) {
        PublicUserResponse creator = userService.getPublicUser(gathering.getCreatorUserId());
        long memberCount = gatheringMemberMapper.countByGatheringId(gathering.getId());
        long momentCount = momentMapper.countByGatheringId(gathering.getId()); // TODO 这里直接持有别的mapper好吗？
        return convertToDetailedResponse(gathering, creator, memberCount, momentCount);
    }

    /**
     * 把已加载的同行、创建者资料和数量拼成列表摘要卡，不查库。
     *
     * @param gathering    同行实体，介绍可为 null
     * @param creatorInfo  创建者公开资料，查不到时传 null，响应里保留 null
     * @param memberCount  有效成员数，由调用方统计后传入
     * @param momentCount  有效关联瞬间数，由调用方统计后传入
     * @return 摘要响应；介绍预览取前 100 个 Unicode 码点，原介绍为 null 时预览为 null
     */
    private GatheringSummaryResponse convertToSummaryResponse(
            Gathering gathering,
            PublicUserResponse creatorInfo,
            long memberCount,
            long momentCount) {
        String description = TextUtils.truncateByCodePoints(gathering.getDescription(), SUMMARY_DESCRIPTION_LENGTH);
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


    /**
     * 批量把同行实体拼成列表摘要，一次补齐创建者资料和两类数量。
     *
     * <p>流程：按创建者去重批量查公开资料 → 批量统计成员数和瞬间数（无记录的同行计 0）→ 逐条组装摘要。
     * 只读查询，无副作用。</p>
     *
     * @param gatherings 非空的同行实体列表，顺序即返回顺序
     * @return 与入参一一对应的摘要列表，创建者资料缺失的 creator 为 null
     */
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
     * 构造成员新增候选记录，只填字段不入库。
     *
     * @param gatheringId 已确认有效的同行 ID
     * @param userId      已确认有效的用户 ID
     * @param savedAt     本次写入时间，UTC，同时给 createdAt 和 updatedAt
     * @return 新 UUIDv7 的候选关系记录，deletedAt 为 null；最终全新插入还是恢复旧关系由 insertOrRestore 决定
     */
    private GatheringMember newMembership(UUID gatheringId, UUID userId, Instant savedAt) {
        // 统一填好新关系的初始字段；最终是全新插入还是恢复已退出的旧关系，由 insertOrRestore 的唯一键冲突处理决定。
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
     * <p>流程：按邀请码加载可加入同行 → 校验本人当前成员标记 → 补齐创建者资料并组装预览。
     * 只读查询，无副作用。</p>
     *
     * @param request 已通过邀请码校验的查询参数
     * @param userId  当前登录用户 ID
     * @return 目标基本信息、创建者公开资料和当前有效成员标记
     * @throws BusinessException 邀请码不存在、同行已解散或邀请关闭时返回 404
     */
    @Transactional(readOnly = true)
    public GatheringJoinPreviewResponse previewJoin(GatheringJoinPreviewRequest request, UUID userId) {
        // 1. 按邀请码加载可加入的同行：未解散、邀请开关还开着
        String inviteCode = request.inviteCode();
        Gathering gathering = gatheringAccessService.getJoinableGatheringByInviteCode(inviteCode);
        // 2. 查本人成员标记和创建者资料，组装预览
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
     * <p>流程：按邀请码加载可加入同行 → 新增或恢复成员关系 → 组装详情。
     * 副作用：gathering_member 同事务写入；已是有效成员时保持原关系不改时间。</p>
     *
     * @param request 已通过邀请码校验的请求，支持直接调用
     * @param userId  当前登录用户 ID，由统一认证过滤器提供
     * @return 当前同行信息和实际成员、瞬间数量；已是有效成员时返回现有结果
     * @throws BusinessException 邀请码不存在、同行已解散或邀请关闭时返回 404
     */
    @Transactional
    public GatheringDetailedResponse joinGathering(JoinGatheringRequest request, UUID userId) {
        // 1. 按邀请码加载可加入的同行：未解散、邀请开关还开着
        String inviteCode = request.inviteCode();
        Gathering gathering = gatheringAccessService.getJoinableGatheringByInviteCode(inviteCode);
        // 2. 新增或恢复成员关系；已是成员则原样保留
        ensureMembership(gathering.getId(), userId);
        // 3. 组装详情返回
        return convertToDetailedResponse(gathering);
    }

    /**
     * 凭同行 ID 和短码确认加入，全部校验与成员写入在一个数据库事务内完成。
     *
     * <p>流程：查询并校验目标（短码一致、邀请开启、创建未满 10 分钟）→ 新增、恢复或沿用成员关系 → 组装当前同行信息。
     * 副作用：gathering_member 同事务写入。附近预览只帮助发现目标，本方法直接根据数据库短码、邀请开关和创建时间判断加入资格。</p>
     *
     * @param request 已校验的同行 ID 与四位短码
     * @param userId  统一认证过滤器提供的用户 ID
     * @return 加入后的同行信息，重复加入保持原关系
     * @throws BusinessException 同行失效、邀请关闭、短码不匹配或已过期时统一返回 404，防枚举
     */
    @Transactional
    public GatheringDetailedResponse joinFromFaceToFace(JoinFaceToFaceRequest request, UUID userId) {
        // 1. 按同行 ID 和短码加载可加入的同行：短码一致、邀请开启、创建未满 10 分钟
        Gathering gathering = gatheringAccessService.getJoinableGatheringByFaceCode(
                request.gatheringId(), request.faceCode());
        // 2. 新增或恢复成员关系；已是成员则原样保留
        ensureMembership(gathering.getId(), userId);
        // 3. 组装详情返回
        return convertToDetailedResponse(gathering);
    }

    /**
     * 确保用户拥有有效成员关系，须在加入业务事务内调用。
     *
     * <p>已是有效成员时直接跳过，审计时间保持原样；已退出的恢复原关系（保留原 ID 与创建时间）。
     * 副作用：gathering_member 新增或恢复一行，跟随外层事务。</p>
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
     * 组装并插入新同行主记录：服务端生成 UUIDv7 ID 和唯一长期邀请码，状态固定进行中、邀请开启。
     *
     * <p>副作用：往 gathering 表插一行，跟随外层事务；邀请码撞唯一索引时整批回滚并抛 409。</p>
     *
     * @param request 已校验并整理名称的请求
     * @param userId  已确认有效的创建者 ID
     * @param savedAt 本次写入时间，UTC，同时给 createdAt 和 updatedAt
     * @return 插入后的同行实体，供成员写入和响应组装复用
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
