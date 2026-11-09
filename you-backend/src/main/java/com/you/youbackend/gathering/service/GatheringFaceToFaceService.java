package com.you.youbackend.gathering.service;

import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.FaceToFaceEntry;
import com.you.youbackend.gathering.model.FaceToFaceMatch;
import com.you.youbackend.gathering.model.FaceToFaceRules;
import com.you.youbackend.gathering.request.FaceToFacePreviewRequest;
import com.you.youbackend.gathering.request.JoinFaceToFaceRequest;
import com.you.youbackend.gathering.request.OpenFaceToFaceRequest;
import com.you.youbackend.gathering.response.FaceToFaceGatheringItem;
import com.you.youbackend.gathering.response.FaceToFaceOpenResponse;
import com.you.youbackend.gathering.response.GatheringResponse;
import com.you.youbackend.gathering.store.GatheringFaceToFaceStore;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 面对面入口开启、附近候选查询与指定目标加入流程。 */
@Log4j2
@Service
@RequiredArgsConstructor
public class GatheringFaceToFaceService {
    private final GatheringFaceToFaceStore store;
    private final GatheringMapper gatheringMapper;
    private final GatheringMemberMapper gatheringMemberMapper;
    private final UserService userService;
    private final GatheringService gatheringService;

    /**
     * 创建者为新建同行开启面对面入口，固定在同行创建后 10 分钟截止。
     * @param gatheringId 已有同行 ID，须仍在创建窗口内
     * @param request 已校验的四位短码与当前坐标
     * @param userId 认证用户 ID，须为同行创建者
     * @return 实际生效的短码与截止时间；重复开启保持首次入口
     */
    public FaceToFaceOpenResponse open(UUID gatheringId, OpenFaceToFaceRequest request, UUID userId) {
        // 1. 校验同行、创建者权限、邀请开关与创建窗口。
        Gathering gathering = requireOpenableGathering(gatheringId, userId);
        Instant expiresAt = gathering.getCreatedAt().toInstant(ZoneOffset.UTC).plus(FaceToFaceRules.WINDOW);
        // 2. 保存临时入口，重复调用复用原短码、发起位置与截止时间。
        FaceToFaceEntry entry = store.open(new FaceToFaceEntry(gatheringId, request.faceCode(),
                request.latitude(), request.longitude(), expiresAt));
        log.debug("面对面入口已就绪，gatheringId={}，userId={}，expiresAt={}", gatheringId, userId, entry.expiresAt());
        return new FaceToFaceOpenResponse(entry.gatheringId(), entry.faceCode(), entry.expiresAt());
    }

    /**
     * 根据短码与位置查找 100 米内可加入同行，返回按距离升序的候选列表。
     * @param request 已校验的短码及当前位置
     * @param userId 认证用户 ID，用于查询当前成员标记
     * @return 候选列表，零匹配时返回空列表，同码多个同行均可展示
     */
    public List<FaceToFaceGatheringItem> preview(FaceToFacePreviewRequest request, UUID userId) {
        // 1. Redis 筛选同码、范围内且未到期的入口。
        List<FaceToFaceMatch> matches = store.findNearby(request.faceCode(), request.latitude(), request.longitude());
        // 2. 有匹配时批量加载展示数据，零匹配直接返回空列表并保留查询日志。
        List<FaceToFaceGatheringItem> candidates = matches.isEmpty() ? List.of() : loadCandidates(matches, userId);
        log.debug("查询附近面对面同行，userId={}，matchedCount={}，candidateCount={}",
                userId, matches.size(), candidates.size());
        return candidates;
    }

    /**
     * 用户选中目标后确认加入，重新检查当前位置、短码及入口有效期。
     * @param request 预览选中的同行 ID、短码及当前坐标
     * @param userId 认证用户 ID
     * @return 加入后的同行信息，成员写入由 GatheringService 的事务统一管理
     */
    public GatheringResponse join(JoinFaceToFaceRequest request, UUID userId) {
        // 1. 重新匹配附近有效入口，确保加入目标就是用户选择的那个同行。
        requireNearbyEntry(request, userId);
        // 2. 再校验目标同行及邀请开关，复用现有成员新增、恢复与完整响应流程。
        return gatheringService.joinFromFaceToFace(request.gatheringId(), userId);
    }

    /**
     * 校验用户选择的同行仍有附近有效入口。
     * @param request 用户选择的同行、短码与当前位置
     * @param userId 当前认证用户 ID，用于记录加入结果
     */
    private void requireNearbyEntry(JoinFaceToFaceRequest request, UUID userId) {
        // 加入时重新匹配当前位置，预览结果仅用于选择目标。
        boolean matched = store.findNearby(request.faceCode(), request.latitude(), request.longitude()).stream()
                .anyMatch(match -> match.entry().gatheringId().equals(request.gatheringId()));
        if (!matched) {
            log.debug("面对面加入未匹配有效入口，gatheringId={}，userId={}，reason=短码失效、入口过期或超出范围",
                    request.gatheringId(), userId);
            throw BusinessException.notFound("FACE_TO_FACE_ENTRY_UNAVAILABLE", "短码已失效、入口已过期或当前位置超出范围");
        }
        log.debug("面对面加入入口校验通过，gatheringId={}，userId={}", request.gatheringId(), userId);
    }

    /**
     * 校验开启入口的业务条件。
     * @param gatheringId 已有同行 ID
     * @param userId 当前认证用户 ID
     * @return 仍在创建窗口内且开放邀请的完整同行实体
     */
    private Gathering requireOpenableGathering(UUID gatheringId, UUID userId) {
        // 1. 同行须有效，开启入口由创建者操作。
        Gathering gathering = gatheringMapper.selectById(gatheringId);
        if (gathering == null) {
            log.debug("开启面对面入口失败，gatheringId={}，userId={}，reason=同行不存在或已解散", gatheringId, userId);
            throw BusinessException.notFound("GATHERING_NOT_FOUND", "同行不存在或已解散");
        }
        if (!gathering.getCreatorUserId().equals(userId)) {
            log.warn("开启面对面入口被拒绝，gatheringId={}，userId={}，reason=当前用户非创建者", gatheringId, userId);
            throw BusinessException.forbidden("只有创建者可以开启面对面加入");
        }
        // 2. 邀请开启且窗口未结束时才可建立入口，截止时间始终依据数据库创建时间。
        if (!Boolean.TRUE.equals(gathering.getInviteEnabled())) {
            log.debug("开启面对面入口失败，gatheringId={}，userId={}，reason=邀请已关闭", gatheringId, userId);
            throw BusinessException.badRequest("GATHERING_INVITATION_DISABLED", "同行邀请已关闭");
        }
        Instant expiresAt = gathering.getCreatedAt().toInstant(ZoneOffset.UTC).plus(FaceToFaceRules.WINDOW);
        if (!expiresAt.isAfter(Instant.now())) {
            log.debug("开启面对面入口失败，gatheringId={}，userId={}，expiresAt={}，reason=创建窗口已结束",
                    gatheringId, userId, expiresAt);
            throw BusinessException.badRequest("FACE_TO_FACE_WINDOW_EXPIRED", "同行创建已超过 10 分钟，面对面加入窗口已结束");
        }
        return gathering;
    }

    /**
     * 批量加载候选展示数据，保持 Redis 返回的距离顺序。
     * @param matches 尚未到期且按距离排序的入口匹配
     * @param userId 当前认证用户 ID
     * @return 同行仍有效、邀请开启且创建者资料可展示的候选列表
     */
    private List<FaceToFaceGatheringItem> loadCandidates(List<FaceToFaceMatch> matches, UUID userId) {
        // 1. 一次读取可加入同行，过滤已解散或邀请关闭的入口。
        List<UUID> ids = matches.stream().map(match -> match.entry().gatheringId()).toList();
        Map<UUID, Gathering> gatherings = gatheringMapper.selectJoinableByIds(ids).stream()
                .collect(Collectors.toMap(Gathering::getId, Function.identity()));
        if (gatherings.isEmpty()) {
            return List.of();
        }
        // 2. 创建者资料、头像与成员标记均批量加载，避免按候选逐项查询。
        Set<UUID> creatorIds = gatherings.values().stream().map(Gathering::getCreatorUserId).collect(Collectors.toSet());
        Map<UUID, PublicUserResponse> creators = userService.getPublicUsers(creatorIds);
        Set<UUID> joinedIds = gatheringMemberMapper.selectActiveGatheringIds(gatherings.keySet(), userId);
        // 3. 按距离顺序组装可展示条目，同码候选全部保留，供用户自主选择。
        return matches.stream().filter(match -> gatherings.containsKey(match.entry().gatheringId()))
                .filter(match -> creators.containsKey(gatherings.get(match.entry().gatheringId()).getCreatorUserId()))
                .map(match -> toCandidate(match, gatherings.get(match.entry().gatheringId()), creators, joinedIds))
                .toList();
    }

    /**
     * 组装一个已经加载完成的候选项。
     * @param match 有效入口与距离
     * @param gathering 有效且开放邀请的同行
     * @param creators 已批量加载的创建者公开资料
     * @param joinedIds 当前用户已加入的候选同行 ID
     * @return 基本信息、距离、截止时间与当前成员标记
     */
    private FaceToFaceGatheringItem toCandidate(FaceToFaceMatch match, Gathering gathering,
                                               Map<UUID, PublicUserResponse> creators, Set<UUID> joinedIds) {
        // 所有信息来自已加载数据，响应组装阶段只进行字段映射。
        return new FaceToFaceGatheringItem(gathering.getId(), gathering.getName(), gathering.getDescription(),
                creators.get(gathering.getCreatorUserId()), match.distanceMeters(), match.entry().expiresAt(),
                joinedIds.contains(gathering.getId()));
    }
}