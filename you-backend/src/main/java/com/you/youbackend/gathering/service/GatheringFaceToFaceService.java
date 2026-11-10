package com.you.youbackend.gathering.service;

import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.FaceToFaceMatch;
import com.you.youbackend.gathering.model.FaceToFaceRules;
import com.you.youbackend.gathering.request.FaceToFacePreviewRequest;
import com.you.youbackend.gathering.request.OpenFaceToFaceRequest;
import com.you.youbackend.gathering.response.FaceToFaceCandidateResponse;
import com.you.youbackend.gathering.response.FaceToFaceOpenResponse;
import com.you.youbackend.gathering.response.FaceToFaceSummaryResponse;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.geo.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 面对面邀请的开启与附近预览。
 * <p>MySQL 永久保存首次短码，Redis GEO 保存发起位置。一个同行只允许成功开启一次，
 * 邀请在同行创建后 10 分钟截止。附近预览帮助用户选择目标，确认加入由 GatheringService 处理。
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class GatheringFaceToFaceService {
    /** 同行介绍摘要最多保留的 Unicode 码点数。 */
    private static final int SUMMARY_DESCRIPTION_LENGTH = 100;

    private final GatheringFaceToFaceRedisService redisService;
    private final GatheringMapper gatheringMapper;
    private final GatheringMemberMapper gatheringMemberMapper;
    private final UserService userService;

    /**
     * 创建者首次开启面对面邀请，保存永久短码和临时位置。
     * <p>流程：校验开启条件 → 首次保存短码 → 写入 GEO 位置 → 返回短码与截止时间。
     * Redis 调用失败时异常继续抛出，数据库短码写入随事务回滚。
     * @param gatheringId 同行 ID
     * @param request 已校验的四位短码与发起位置
     * @param userId 认证用户 ID，须为同行创建者
     * @return 同行 ID、首次短码与固定截止时间
     */
    @Transactional
    public FaceToFaceOpenResponse open(UUID gatheringId, OpenFaceToFaceRequest request, UUID userId) {
        // 1. 确认创建者、邀请开关、一次开启限制及创建时间窗口。
        Instant now = Instant.now();
        Gathering gathering = requireOpenableGathering(gatheringId, userId, now);
        // 2. 数据库条件更新保证首次登记，随后写入发起位置；异常时数据库写入回滚。
        saveFaceCode(gatheringId, request.faceCode(), now);
        redisService.saveLocation(
                gatheringId,
                request.faceCode(),
                gathering.getCreatedAt().toInstant(ZoneOffset.UTC),
                new Point(request.longitude(), request.latitude()));
        // 3. 返回业务截止时间，日志描述当前事务内已完成的写入。
        FaceToFaceOpenResponse response = toOpenResponse(gathering, request.faceCode());
        log.info("已写入面对面短码和位置，gatheringId={}，userId={}，expiresAt={}",
                gatheringId, userId, response.expiresAt());
        return response;
    }

    /**
     * 查找同码、100 米内且仍可加入的同行。
     * <p>流程：查询两个 GEO 时间段 → MySQL 校验短码、邀请开关和有效期 → 加载资料并组装候选。
     * 每次查询统一使用一个时间点，零匹配返回空列表，多个匹配全部展示。
     * @param request 已校验的短码与当前位置
     * @param userId 认证用户 ID，用于查询成员标记
     * @return 按距离升序的候选列表
     */
    public List<FaceToFaceCandidateResponse> preview(FaceToFacePreviewRequest request, UUID userId) {
        // 1. GEO 只提供位置候选，实际邀请有效性由数据库决定。
        Instant now = Instant.now();
        List<FaceToFaceMatch> matches = redisService.findNearby(
                request.faceCode(), request.latitude(), request.longitude(), now);
        if (matches.isEmpty()) {
            log.debug("查询附近面对面同行，userId={}，matchedCount=0，candidateCount=0", userId);
            return List.of();
        }
        // 2. 数据库筛出有效同行，再补齐展示资料并按距离排序。
        List<Gathering> gatherings = findValidGatherings(matches, request.faceCode(), now);
        List<FaceToFaceCandidateResponse> candidates = buildCandidateResponses(gatherings, matches, userId);
        log.debug("查询附近面对面同行，userId={}，matchedCount={}，candidateCount={}",
                userId, matches.size(), candidates.size());
        return candidates;
    }

    /**
     * 校验开启条件，重复开启直接返回冲突。
     * @param gatheringId 同行 ID
     * @param userId 当前认证用户 ID
     * @param now 本次开启时间
     * @return 已确认可以首次开启的同行
     */
    private Gathering requireOpenableGathering(UUID gatheringId, UUID userId, Instant now) {
        // 1. 查询有效同行，开启权限由创建者身份决定。
        Gathering gathering = gatheringMapper.selectById(gatheringId);
        if (gathering == null) {
            throw BusinessException.notFound("GATHERING_NOT_FOUND", "同行不存在或已解散");
        }
        if (!gathering.getCreatorUserId().equals(userId)) {
            log.warn("开启面对面邀请被拒绝，gatheringId={}，userId={}，reason=当前用户非创建者", gatheringId, userId);
            throw BusinessException.forbidden("只有创建者可以开启面对面加入");
        }
        // 2. 首次短码永久保留，已开启的同行后续始终按重复开启处理。
        if (gathering.getFaceCode() != null) {
            log.debug("重复开启面对面邀请，gatheringId={}，userId={}", gatheringId, userId);
            throw BusinessException.conflict("FACE_TO_FACE_ALREADY_OPENED", "该同行已开启过面对面邀请");
        }
        // 3. 仅在邀请开启且创建后 10 分钟内允许首次登记。
        if (!Boolean.TRUE.equals(gathering.getInviteEnabled())) {
            throw BusinessException.badRequest("GATHERING_INVITATION_DISABLED", "同行邀请已关闭");
        }
        if (!gathering.getCreatedAt().toInstant(ZoneOffset.UTC).plus(FaceToFaceRules.WINDOW).isAfter(now)) {
            log.debug("开启面对面邀请失败，gatheringId={}，userId={}，reason=创建窗口已结束", gatheringId, userId);
            throw BusinessException.badRequest("FACE_TO_FACE_WINDOW_EXPIRED", "面对面加入窗口已结束");
        }
        return gathering;
    }

    /**
     * 永久登记首次短码，更新条件处理两个请求同时通过前置检查的情况。
     * @param gatheringId 已校验的同行 ID
     * @param faceCode 已校验的四位短码
     * @param now 本次写入时间
     */
    private void saveFaceCode(UUID gatheringId, String faceCode, Instant now) {
        // face_code IS NULL 保证只成功登记一次，更新失败时结束当前请求。
        LocalDateTime savedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        if (gatheringMapper.setFaceCodeIfAbsent(gatheringId, faceCode, savedAt) == 0) {
            log.debug("首次短码登记冲突，gatheringId={}", gatheringId);
            throw BusinessException.conflict("FACE_TO_FACE_ALREADY_OPENED", "该同行已开启过面对面邀请");
        }
    }

    /**
     * 根据附近匹配的 ID，批量查询仍可通过当前短码加入的同行。
     * @param matches 非空的 GEO 匹配，包含同行 ID 与距离
     * @param faceCode 本次输入的短码
     * @param now 与 GEO 查询一致的业务时间
     * @return 短码一致、邀请开启、未解散且创建未满 10 分钟的同行实体
     */
    private List<Gathering> findValidGatherings(List<FaceToFaceMatch> matches, String faceCode, Instant now) {
        // 按 ID 批量查询，并由数据库统一筛选短码、邀请开关和有效期；软删除沿用全局配置。
        List<UUID> ids = matches.stream().map(FaceToFaceMatch::gatheringId).toList();
        LocalDateTime createdAfter = LocalDateTime.ofInstant(now.minus(FaceToFaceRules.WINDOW), ZoneOffset.UTC);
        return gatheringMapper.selectFaceToFaceCandidates(ids, faceCode, createdAfter);
    }

    /**
     * 为有效同行批量补充创建者资料、成员标记和距离，生成附近候选列表。
     * @param gatherings 已通过数据库筛选的同行，可为空
     * @param matches 本次 GEO 匹配，包含所有有效同行的距离
     * @param userId 当前认证用户 ID，用于查询成员标记
     * @return 按距离升序的候选列表；沿用创建者资料缺失时隐藏候选的行为
     */
    private List<FaceToFaceCandidateResponse> buildCandidateResponses(
            List<Gathering> gatherings, List<FaceToFaceMatch> matches, UUID userId) {
        // 1. 没有有效同行时直接返回空列表。
        if (gatherings.isEmpty()) {
            return List.of();
        }
        // 2. 按同行 ID 索引距离，批量加载创建者资料与成员标记。
        Map<UUID, Double> distances = matches.stream()
                .collect(Collectors.toMap(FaceToFaceMatch::gatheringId, FaceToFaceMatch::distanceMeters));
        Set<UUID> creatorIds = gatherings.stream().map(Gathering::getCreatorUserId).collect(Collectors.toSet());
        Map<UUID, PublicUserResponse> creators = userService.getPublicUsers(creatorIds);
        List<UUID> gatheringIds = gatherings.stream().map(Gathering::getId).toList();
        Set<UUID> joinedIds = gatheringMemberMapper.selectActiveGatheringIds(gatheringIds, userId);
        // 3. 直接遍历有效同行组装响应；创建者资料缺失时沿用隐藏该候选的现有行为。
        List<FaceToFaceCandidateResponse> candidates = new ArrayList<>();
        for (Gathering gathering : gatherings) {
            PublicUserResponse creator = creators.get(gathering.getCreatorUserId());
            if (creator == null) {
                continue;
            }
            candidates.add(toCandidateResponse(gathering, creator, distances.get(gathering.getId()),
                    joinedIds.contains(gathering.getId())));
        }
        // 4. 数据库返回顺序与距离无关，统一按距离升序展示。
        candidates.sort(Comparator.comparingDouble(FaceToFaceCandidateResponse::distanceMeters));
        return candidates;
    }

    /**
     * 将已加载的数据组装为一个附近候选。
     * @param gathering 已通过数据库筛选的同行
     * @param creator 已加载的创建者公开资料
     * @param distanceMeters GEO 返回的距离，单位米
     * @param isMember 当前用户是否为有效成员
     * @return 同行摘要、距离、固定截止时间及成员标记
     */
    private FaceToFaceCandidateResponse toCandidateResponse(
            Gathering gathering, PublicUserResponse creator, double distanceMeters, boolean isMember) {
        // 将同行摘要与本次附近查询的信息组合，截止时间取同行创建后 10 分钟。
        return new FaceToFaceCandidateResponse(toSummaryResponse(gathering, creator), distanceMeters,
                gathering.getCreatedAt().toInstant(ZoneOffset.UTC).plus(FaceToFaceRules.WINDOW), isMember);
    }

    /**
     * 将同行实体和已加载的创建者资料转换为摘要，介绍最多保留前 100 个 Unicode 码点。
     * @param gathering 同行实体，介绍可为空
     * @param creator 已加载的创建者公开资料
     * @return 用于列表展示的同行摘要，实体中的完整介绍保持原样
     */
    private FaceToFaceSummaryResponse toSummaryResponse(Gathering gathering, PublicUserResponse creator) {
        // 按码点截取介绍，确保 emoji 等补充字符保持完整；未填写介绍时保留 null。
        String description = gathering.getDescription();
        if (description != null) {
            int end = description.offsetByCodePoints(0,
                    Math.min(SUMMARY_DESCRIPTION_LENGTH, description.codePointCount(0, description.length())));
            description = description.substring(0, end);
        }
        return new FaceToFaceSummaryResponse(gathering.getId(), gathering.getName(), description, creator);
    }

    /**
     * 组装开启响应。
     * @param gathering 已通过开启条件校验的同行
     * @param faceCode 已成功登记的短码
     * @return 固定在同行创建后 10 分钟截止的邀请信息
     */
    private FaceToFaceOpenResponse toOpenResponse(Gathering gathering, String faceCode) {
        // 截止时间依据创建时间，GEO 集合的清理时间单独计算。
        return new FaceToFaceOpenResponse(gathering.getId(), faceCode,
                gathering.getCreatedAt().toInstant(ZoneOffset.UTC).plus(FaceToFaceRules.WINDOW));
    }
}