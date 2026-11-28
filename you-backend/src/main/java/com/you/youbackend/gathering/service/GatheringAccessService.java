package com.you.youbackend.gathering.service;

import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.util.TimeUtils;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.gathering.model.FaceToFaceRules;
import com.you.youbackend.gathering.model.GatheringStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** 成员访问、创建者操作、添加瞬间和加入同行的资格校验，返回已鉴权的同行实体。 */
@Log4j2
@Service
@RequiredArgsConstructor
public class GatheringAccessService {
    private final GatheringMapper gatheringMapper;
    private final GatheringMemberMapper gatheringMemberMapper;

    /**
     * 校验用户能否向指定同行关联本人瞬间，由发布等业务在写入前调用。
     *
     * <p>流程：查询有效同行 → 校验未结束 → 校验本人有效成员关系。
     * 只读查询，无副作用。</p>
     *
     * @param gatheringId 请求中已提供的同行 ID
     * @param userId      当前登录用户 ID
     * @throws BusinessException 同行不存在或已解散返回 404，已结束返回 400，非有效成员返回 403
     */
    public void validateCanAddMoment(UUID gatheringId, UUID userId) {
        // 1. 加载同行主记录，查不到按已解散或不存在报 404；已解散的记录查不出来（软删自动过滤）。
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
     * 读取有效同行并校验当前用户为创建者，供修改、结束、解散等管理入口共用。
     *
     * <p>流程：查询有效同行 → 校验创建者身份。
     * 只读查询，无副作用。</p>
     *
     * @param gatheringId 同行 ID
     * @param userId      当前认证用户 ID
     * @return 已确认有效且本人为创建者的同行
     * @throws BusinessException 同行不存在或已解散返回 404，非创建者返回 403
     */
    public Gathering getCreatorGathering(UUID gatheringId, UUID userId) {
        Gathering gathering = getGathering(gatheringId);
        if (!gathering.getCreatorUserId().equals(userId)) {
            log.warn("操作同行被拒绝，gatheringId={}，userId={}，reason=不是创建者", gatheringId, userId);
            throw BusinessException.forbidden("仅创建者可以操作");
        }
        return gathering;
    }

    /**
     * 读取有效同行并校验当前用户成员资格，供同行和内容读取入口共用。
     *
     * <p>流程：查询有效同行 → 校验本人有效成员关系；邀请开关及结束状态不影响已加入成员访问。
     * 只读查询，无副作用。</p>
     *
     * @param gatheringId 同行 ID
     * @param userId      当前认证用户 ID
     * @return 已确认有效且本人为有效成员的同行
     * @throws BusinessException 同行不存在或已解散返回 404，非有效成员返回 403
     */
    public Gathering getMemberGathering(UUID gatheringId, UUID userId) {
        // 先加载同行：缺失或已解散报 404。再查成员关系：非成员（含已退出）拦 403。
        // 邀请开关和已结束状态都不影响老成员的访问资格。
        Gathering gathering = getGathering(gatheringId);
        if (!gatheringMemberMapper.existsByGatheringIdAndUserId(gatheringId, userId)) {
            log.warn("访问同行被拒绝，gatheringId={}，userId={}，reason=非有效成员", gatheringId, userId);
            throw BusinessException.forbidden("仅同行有效成员可以访问");
        }
        return gathering;
    }

    /**
     * 从数据库校验面对面加入资格，凭同行 ID 和四位短码定位目标。
     *
     * <p>流程：查询有效同行 → 校验短码一致、邀请开启且创建未满 10 分钟。
     * 只读查询，无副作用。短码永久保存但仅在窗口内可用于加入。</p>
     *
     * @param gatheringId 用户选择的同行 ID
     * @param faceCode    用户提交的四位短码
     * @return 短码匹配、邀请开启且创建未满 10 分钟的同行
     * @throws BusinessException 同行失效、邀请关闭、短码不匹配或已过期时统一返回 404，防枚举
     */
    Gathering getJoinableGatheringByFaceCode(UUID gatheringId, String faceCode) {
        // 加载同行后逐项校验：短码一致、邀请开启、创建未满 10 分钟，任一不符统一报 404 防枚举。
        // 短码永久保存，但只有创建后 10 分钟内能用来加入。
        Gathering gathering = gatheringMapper.selectById(gatheringId);
        Instant now = TimeUtils.now();
        if (gathering == null || !Boolean.TRUE.equals(gathering.getInviteEnabled())
                || !faceCode.equals(gathering.getFaceCode())
                || !gathering.getCreatedAt().plus(FaceToFaceRules.WINDOW).isAfter(now)) {
            log.debug("面对面加入校验失败，gatheringId={}，reason=同行失效、邀请关闭、短码不匹配或已过期", gatheringId);
            throw BusinessException.notFound("FACE_TO_FACE_INVITATION_UNAVAILABLE", "同行不可加入、短码不匹配或邀请已过期");
        }
        return gathering;
    }

    /**
     * 根据邀请码加载开放邀请的同行；结束操作会关闭邀请。
     *
     * <p>流程：按邀请码查询有效同行 → 校验邀请仍开启。
     * 只读查询，无副作用。</p>
     *
     * @param inviteCode 已通过边界校验的长期邀请码
     * @return 未解散且开放邀请的完整同行实体
     * @throws BusinessException 目标不可加入时统一返回 404，防枚举
     */
    Gathering getJoinableGatheringByInviteCode(String inviteCode) {
        // 按邀请码加载同行（缺失或已解散报 404），再校验邀请开关还开着；两项不合统一报 404 防枚举。
        Gathering gathering = gatheringMapper.selectByInviteCode(inviteCode);
        if (gathering == null || !Boolean.TRUE.equals(gathering.getInviteEnabled())) {
            throw BusinessException.notFound("GATHERING_INVITATION_UNAVAILABLE", "邀请码无效、邀请已关闭或同行已解散");
        }
        return gathering;
    }

    /**
     * 读取有效同行，软删除由全局配置过滤，缺失返回 404。
     *
     * <p>只读查询，无副作用。同行不存在和已解散使用统一业务语义。</p>
     *
     * @param gatheringId 同行 ID
     * @return 完整同行实体
     * @throws BusinessException 同行不存在或已解散返回 404
     */
    Gathering getGathering(UUID gatheringId) {
        // 同行不存在和已解散使用统一业务语义。
        Gathering gathering = gatheringMapper.selectById(gatheringId);
        if (gathering == null) {
            throw BusinessException.notFound("GATHERING_NOT_FOUND", "同行不存在或已解散");
        }
        return gathering;
    }
}
