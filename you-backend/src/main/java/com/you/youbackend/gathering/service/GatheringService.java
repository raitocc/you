package com.you.youbackend.gathering.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.entity.GatheringMember;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.GatheringStatus;
import com.you.youbackend.gathering.request.CreateGatheringRequest;
import com.you.youbackend.gathering.request.GatheringJoinPreviewRequest;
import com.you.youbackend.gathering.request.JoinGatheringRequest;
import com.you.youbackend.gathering.response.GatheringJoinPreviewResponse;
import com.you.youbackend.gathering.response.GatheringResponse;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 同行创建、邀请码加入预览与成员加入流程。 */
@Log4j2
@Service
@RequiredArgsConstructor
public class GatheringService {
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
     * 面对面入口校验完成后加入指定同行，与邀请码加入共用成员维护及响应组装。
     * @param gatheringId 调用方已校验临时入口、短码和距离的同行 ID
     * @param userId 统一认证过滤器提供的用户 ID
     * @return 加入后的同行信息，重复加入保持原关系
     */
    @Transactional
    public GatheringResponse joinFromFaceToFace(UUID gatheringId, UUID userId) {
        // 1. 重新读取同行与邀请开关，确认目标当前仍可加入。
        Gathering gathering = gatheringMapper.selectById(gatheringId);
        if (gathering == null || !Boolean.TRUE.equals(gathering.getInviteEnabled())) {
            log.debug("面对面加入目标已失效，gatheringId={}，userId={}，reason=同行已解散或邀请已关闭", gatheringId, userId);
            throw BusinessException.notFound("GATHERING_INVITATION_UNAVAILABLE", "同行已解散或邀请已关闭");
        }
        // 2. 复用成员新增、恢复及最新数量响应，写入由当前事务统一管理。
        ensureMembership(gatheringId, userId);
        return loadGatheringResponse(gathering);
    }

    /**
     * 根据邀请码加载可加入的同行，进行中和已结束采用相同加入规则。
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