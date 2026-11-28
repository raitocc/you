package com.you.youbackend.moment.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.common.util.TextUtils;
import com.you.youbackend.common.util.TimeUtils;
import com.you.youbackend.gathering.event.GatheringMomentsLinkedEvent;
import com.you.youbackend.gathering.model.GatheringMomentCursor;
import com.you.youbackend.gathering.service.GatheringAccessService;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.entity.MomentImage;
import com.you.youbackend.moment.entity.MomentTag;
import com.you.youbackend.moment.event.MomentDeletedEvent;
import com.you.youbackend.moment.mapper.MomentImageMapper;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.mapper.MomentTagMapper;
import com.you.youbackend.moment.model.MomentFeedCursor;
import com.you.youbackend.moment.model.MomentLocation;
import com.you.youbackend.moment.request.CreateMomentRequest;
import com.you.youbackend.moment.request.UpdateMomentRequest;
import com.you.youbackend.moment.response.MomentDetailedResponse;
import com.you.youbackend.moment.response.MomentSummaryResponse;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Log4j2
@Service
@RequiredArgsConstructor
public class MomentService {

    private static final int MAX_BODY_BYTES = 65_535;
    private static final int MAX_TAG_LENGTH = 64;
    private static final int COORDINATE_SCALE = 7;

    private final MomentMapper momentMapper;
    private final MomentImageMapper momentImageMapper;
    private final MomentTagMapper momentTagMapper;
    private final MediaService mediaService;
    private final GatheringAccessService gatheringAccessService;
    private final UserService userService;
    private final ApplicationEventPublisher events;
    private final AlbumMomentMapper albumMomentMapper;


    /**
     * 分页浏览本人的瞬间，返回摘要卡片和下一页游标。
     *
     * <p>流程：按“发生时间、ID 倒序”查一页多一条 → 空页直接返回 → 只对本页记录批量取图片和作者、组装卡片 → 多出的那条只用来判断还有没有下一页（不给它加载图片），游标取本页最后一条。
     * 只读事务，无副作用。</p>
     *
     * @param userId  当前登录用户 ID，只查本人的有效瞬间
     * @param request 已校验的分页请求；游标为空表示首屏，limit 为本页最大条数
     * @return 分页结果：items 为摘要卡片，nextCursor 为下一页边界（末页为 null），hasMore 表示还有没有下一页
     * @throws BusinessException 游标时间超出数据库可存范围时抛出（{@code INVALID_ARGUMENT}）
     */
    @Transactional(readOnly = true)
    public ResultPage<MomentSummaryResponse, MomentFeedCursor> browseYours(
            UUID userId,
            PageRequest<MomentFeedCursor> request) {
        // 1. 通过游标查到数据库中的 Moment 实体
        MomentFeedCursor cursor = request.cursor();
        Instant cursorTime = cursor == null ? null : cursor.memoryTime();
        UUID cursorId = (cursor == null) ? null : cursor.id();
        List<Moment> rows = momentMapper.selectPageAfterCursor(userId, cursorTime, cursorId, request.limit() + 1);
        // 2. 空集直接返回
        if (rows.isEmpty()) {
            log.debug("浏览本人瞬间，userId={}，limit={}，itemCount=0，hasMore=false", userId, request.limit());
            return ResultPage.empty();
        }

        // 3. 将 List<Moment> rows 转换为 List<MomentSummaryResponse> 对象，顺序不变
        List<Moment> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        List<MomentSummaryResponse> momentSummaryResponses = convertToSummaryResponse(page);

        // 4. 构造分页响应
        boolean hasMore = rows.size() > request.limit();
        log.debug("浏览本人瞬间，userId={}，limit={}，momentCount={}，hasMore={}",
                userId, request.limit(), momentSummaryResponses.size(), hasMore);
        return ResultPage.toResultPage(
                momentSummaryResponses,
                hasMore,
                last -> new MomentFeedCursor(last.memoryTime(), last.id()));
    }

    /**
     * 发布一条瞬间：校验图文、标签、地点和时间，写主记录和图片、标签关联，返回完整详情。
     * 图片必须已直传并登记好，这里只确认可用性并建关联；空白标题存 null。
     *
     * <p>流程：校验图文至少一种 → 带同行则校验同行可关联 → 整理标签、地点、时间 → 批量确认图片本人可用 → 写主记录 → 写图片关联 → 写标签关联 → 带同行则发“已加入同行”事件 → 组装详情。
     * 副作用：主记录和两张关联表在同一事务写入，失败整体回滚。</p>
     *
     * @param request 已由 MVC 校验过的发布请求，瞬间 ID 由客户端按 UUIDv7 生成
     * @param userId  当前登录用户 ID，作作者和图片归属校验用
     * @return 刚保存的完整详情，图片顺序和提交一致
     * @throws BusinessException 内容、标签、地点、时间或图片不合规（400），瞬间 ID 被占（409，已软删除的记录也占 ID），或同行不可关联
     */
    // TODO 完全不需要返回一大堆，直接返回success
    @Transactional
    public MomentDetailedResponse createMoment(CreateMomentRequest request, UUID userId) {
        // 1. 验证内容，图片与文字必须至少有一个
        List<UUID> imageFileIds = request.imageFileIds();
        validateMomentContent(request.body(), imageFileIds);
        // 2. 验证同行权限
        if (request.gatheringId() != null) {
            gatheringAccessService.validateCanAddMoment(request.gatheringId(), userId);
        }
        // 3. 验证并标准化标签内容
        List<String> tags = validateTags(request.tags());
        // 4. 处理位置信息
        MomentLocation location = validateLocation(request.location());
        // 5. 处理时间信息
        Instant memoryTime = request.memoryTime();
        // 6. 批量校验图片均为本人有效图片（只验可用性，不做复用检查）
        mediaService.validateOwnedImages(imageFileIds, userId);
        // 7. 审计字段
        Instant savedAt = TimeUtils.now();
        // 8.1 保存 moment 到表中
        Moment moment = saveNewMoment(request, userId, memoryTime, location, savedAt);
        // 8.2 保存 image 到关联表
        saveNewMomentImages(moment.getId(), imageFileIds, savedAt);
        // 8.3 保存 tag 到关联表
        List<String> savedTags = saveNewMomentTags(moment.getId(), tags, savedAt);
        // 9. 发布事件：【一个或多个瞬间已加入同行】
        if (moment.getGatheringId() != null) {
            events.publishEvent(new GatheringMomentsLinkedEvent(
                    moment.getGatheringId(), userId, List.of(moment.getId()), savedAt
            ));
        }
        // 10. 构造响应
        return convertToDetailedResponse(moment);
    }

    /**
     * 全量更新本人瞬间：请求覆盖主记录可编辑字段，图片和标签按本次提交整体替换，返回完整详情。
     * 图片、标签传空列表就是清空；图片只解除关联，文件保留；历史关联的 ID 和创建时间保留，之后可恢复。
     *
     * <p>流程：归属校验 → 校验图文 → 整理标签、地点、时间 → 批量确认图片可用 → 更新主记录 → 同步图片关联 → 同步标签关联 → 组装详情。
     * 副作用：主记录和关联在同一事务更新，失败整体回滚。</p>
     *
     * @param momentId 待编辑瞬间 ID
     * @param request  全量请求；标题、正文、心情、地点传 null 表示清空，图片、标签必须传列表（空列表=清空）
     * @param userId   当前登录用户 ID
     * @return 更新后的完整详情，图片顺序和本次提交一致
     * @throws BusinessException 瞬间不存在或非本人（404/403），内容、标签、时间、图片不合规（400）
     */
    // TODO 其实这个也完全没必要，前端再查一下就好了
    @Transactional
    public MomentDetailedResponse updateMoment(UUID momentId, UpdateMomentRequest request, UUID userId) {
        // 1. 权限验证
        Moment moment = getOwnedMoment(momentId, userId);
        // 2. 验证内容，图片与文字必须至少有一个
        List<UUID> imageFileIds = request.imageFileIds();
        validateMomentContent(request.body(), imageFileIds);
        // 3. 验证并处理标签
        List<String> tags = validateTags(request.tags());
        // 4. 处理位置信息
        MomentLocation location = validateLocation(request.location());
        // 5. 处理时间信息
        Instant memoryTime = request.memoryTime();
        // 6. 批量校验图片均为本人有效图片（只验可用性，不做复用检查）
        mediaService.validateOwnedImages(imageFileIds, userId);
        // 7. 审计字段
        Instant savedAt = TimeUtils.now();
        // 8.1 处理moment本体
        updateMoment(moment, request, memoryTime, location, savedAt);
        // 8.2 处理image到关联表 TODO 写得很烂
        syncMomentImages(momentId, imageFileIds, savedAt);
        // 8.3 处理tag到关联表 TODO 写得很烂
        syncMomentTags(momentId, tags, savedAt);
        log.info("已修改瞬间，momentId={}，userId={}，imageCount={}",
                momentId, userId, imageFileIds.size());
        return convertToDetailedResponse(moment);
    }

    /**
     * 查一条本人瞬间的完整详情
     *
     * <p>流程：归属校验 → 读图片并签临时地址 → 读标签和作者资料 → 组装详情。
     * 只读事务，无副作用。</p>
     *
     * @param momentId 瞬间 ID
     * @param userId   当前登录用户 ID
     * @return 完整详情，图片按展示顺序
     * @throws BusinessException 瞬间不存在或非本人时抛出（404/403）
     */
    @Transactional(readOnly = true)
    public MomentDetailedResponse getMomentDetail(UUID momentId, UUID userId) {
        // 1. 获取已鉴权的 moment
        Moment moment = getOwnedMoment(momentId, userId);
        // 2. 转换为响应对象
        MomentDetailedResponse response = convertToDetailedResponse(moment);
        log.debug("查询瞬间详情，momentId={}，userId={}", momentId, userId);
        return response;
    }

    /**
     * 软删除本人瞬间，删过的再删直接返回（幂等）；媒体文件保留。
     *
     * <p>流程：归属校验（含已删除记录）→ 已删过就返回 → 软删主记录 → 解除图片关联 → 解除标签关联 → 发“瞬间已删除”事件。
     * 副作用：三处软删在同一事务提交，失败整体回滚。</p>
     *
     * @param momentId 瞬间 ID
     * @param userId   当前登录用户 ID
     * @throws BusinessException 瞬间不存在或不属于当前用户时抛出（404/403）
     */
    @Transactional
    public void deleteMoment(UUID momentId, UUID userId) {
        // 1. 鉴权
        Moment moment = getOwnedMoment(momentId, userId, true);
        // 2. 幂等
        if (moment.getDeletedAt() != null) {
            return;
        }
        // 3. 审计字段
        Instant deletedAt = TimeUtils.now();
        // 4.1 删除moment TODO Mapper按道理会自动处理软删除
        momentMapper.softDeleteById(momentId, deletedAt);
        // 4.2 删除image关联
        momentImageMapper.removeAssociationsByMomentId(momentId, deletedAt);
        // 4.3 删除tag关联
        momentTagMapper.removeAssociationsByMomentId(momentId, deletedAt);
        // 4.4 清理albummoment关联
        albumMomentMapper.removeAssociationsByMomentId(momentId, deletedAt);

        // 5. 发布事件：【一个瞬间已删除】
        events.publishEvent(new MomentDeletedEvent(
                momentId,
                deletedAt
        ));

        log.info("已删除瞬间，momentId={}，userId={}", momentId, userId);
    }


    /**
     * 把一页瞬间实体批量组装成摘要卡片，顺序和入参一致。
     *
     * <p>流程：查每个瞬间的图片关联（按展示顺序）→ 取第一张图签好名当封面 → 批量查作者公开资料 → 逐条拼卡片。
     * 只读查询，无副作用。</p>
     *
     * @param moments 瞬间实体列表，至少填 ID、作者、标题、正文、心情、发生时间；调用方保证非空
     * @return 与入参同序等长的摘要卡片；没有图的瞬间封面为 null、图片数为 0
     * @throws IllegalArgumentException moments 为 null 或空（属服务端缺陷，全局异常处理器转 500）
     */
    public List<MomentSummaryResponse> convertToSummaryResponse(List<Moment> moments) {
        Assert.notEmpty(moments, "至少传入一个 moment");

        // 1.1 查询到所有 moment 的所有 images
        List<UUID> momentIds = moments.stream().map(Moment::getId).toList();
        Map<UUID, List<MomentImage>> imagesByMomentId = momentImageMapper.selectMomentsImages(momentIds);
        // 1.2 拿到封面文件的 key，全部签名
        List<UUID> coverFileIds = imagesByMomentId.values().stream()
                .filter(images -> !images.isEmpty())
                .map(images -> images.get(0).getFileId())
                .distinct()
                .toList();
        Map<UUID, String> coverUrlsByFileId = mediaService.getReadUrls(coverFileIds);

        // 3. 批量获取作者资料
        List<UUID> autherUserId = moments.stream().map(Moment::getAuthorUserId).toList();
        Map<UUID, PublicUserResponse> authorsByUserId = userService.getPublicUsers(autherUserId);

        // 3. 构造响应
        List<MomentSummaryResponse> summaryResponses = new ArrayList<>();
        for (Moment moment : moments) {
            // 3.1 拿到 coverUrl
            UUID momentId = moment.getId();
            List<MomentImage> images = imagesByMomentId.get(momentId);
            String coverUrl = null;
            if (!images.isEmpty()) {
                UUID fileId = images.get(0).getFileId();
                coverUrl = coverUrlsByFileId.get(fileId);
                if (coverUrl == null) {
                    // 走到这里只可能是“有图却失效”：违反“关联建立后文件不消失”的规则；没图的情况到不了这。
                    log.error("封面签不出地址，关联指向了失效文件，momentId={}，fileId={}", momentId, fileId);
                    throw BusinessException.internalError("MEDIA_INCONSISTENT", "图片关联指向了失效文件");
                }
            }
            // 3.2 拿到 imageCount
            int imageCount = images.size();
            // 3.3 拿到作者信息
            PublicUserResponse authorInfo = authorsByUserId.get(moment.getAuthorUserId());
            // 3.4 构造响应对象
            MomentSummaryResponse summary = convertToSummaryResponse(moment, coverUrl, imageCount, authorInfo);
            summaryResponses.add(summary);
        }
        return summaryResponses;
    }

    /**
     * 拼单条摘要卡片，不查库，封面地址由调用方签好名传进来。
     *
     * <p>流程：正文截前 100 个 Unicode 码点当预览（空正文预览为 null）→ 拼卡片。
     * 不改数据，无副作用。</p>
     *
     * @param moment     瞬间实体，用到 ID、标题、正文、心情、发生时间
     * @param coverUrl   已签名的第一张图地址，没有图时为 null
     * @param imageCount 这条瞬间的图片总数
     * @param authorInfo 作者公开资料，查不到时为 null
     * @return 摘要卡片
     */
    private MomentSummaryResponse convertToSummaryResponse(
            Moment moment,
            String coverUrl,
            int imageCount,
            PublicUserResponse authorInfo) {
        return MomentSummaryResponse.builder()
                .id(moment.getId())
                .title(moment.getTitle())
                .bodyPreview(TextUtils.truncateByCodePoints(moment.getBody(), 100)) // TODO 预览长度不能在这里写死 / 约定 null 或者空串
                .coverUrl(coverUrl)
                .imageCount(imageCount)
                .mood(moment.getMood())
                .memoryTime(moment.getMemoryTime())
                .author(authorInfo)
                .build();
    }

    /**
     * 查库组装瞬间详情：图片签名、标签、地点、作者资料都现读现拼。
     *
     * <p>只读查询，无副作用。</p>
     *
     * @param moment 完整瞬间实体，时间字段为 UTC
     * @return 详情响应，时间为 UTC Instant；注意地点四字段全空时也返回空地点对象，不归 null（和 toResponse 行为不同）
     * @throws NullPointerException moment 为 null（属服务端缺陷，转 500）
     */
    public MomentDetailedResponse convertToDetailedResponse(Moment moment) {
        Objects.requireNonNull(moment, "moment 为空");
        UUID momentId = moment.getId();
        // 1. 获取所有图片并签名
        List<MomentImage> momentImages = momentImageMapper.selectMomentImages(momentId);
        List<UUID> imageIds = momentImages.stream().map(MomentImage::getFileId).toList();
        Map<UUID, String> readUrlByFileId = mediaService.getReadUrls(imageIds);
        // 2. 构造图片列表
        List<MomentDetailedResponse.Image> images = new ArrayList<>();
        for (UUID fileId : imageIds) {
            images.add(new MomentDetailedResponse.Image(fileId, readUrlByFileId.get(fileId)));
        }
        // 3. 处理 tag 列表
        List<MomentTag> momentTags = momentTagMapper.selectMomentTags(momentId);
        List<String> tagNames = momentTags.stream().map(MomentTag::getTagName).toList();
        // 4. 处理 MomentLocation
        MomentLocation location = new MomentLocation( // TODO 关于 location 的业务规则需要完善
                moment.getLatitude(), moment.getLongitude(), moment.getLocationName(), moment.getLocationAddress());

        PublicUserResponse publicUser = userService.getPublicUser(moment.getAuthorUserId());

        return MomentDetailedResponse.builder().id(momentId)
                .title(moment.getTitle())
                .body(moment.getBody())
                .images(images)
                .tags(tagNames)
                .mood(moment.getMood())
                .memoryTime(moment.getMemoryTime())
                .location(location)
                .createdAt(moment.getCreatedAt())
                .updatedAt(moment.getUpdatedAt())
                .author(publicUser)
                .build();
    }


    private Moment getMoment(UUID momentId) {
        Moment moment = momentMapper.selectById(momentId);
        if (moment == null) {
            log.debug("瞬间查询未命中");
            throw BusinessException.notFound("MOMENT_NOT_FOUND", "瞬间不存在");
        }
        return moment;
    }

    /**
     * 按 ID 查瞬间，并确认是不是当前用户自己的（默认不查已删除的）。
     *
     * <p>查不到抛 404，不是自己的抛 403；只读查询，无副作用。</p>
     *
     * @param momentId    瞬间 ID
     * @param ownerUserId 当前用户 ID
     * @return 属于该用户的瞬间实体
     * @throws BusinessException 瞬间不存在或不属于该用户时抛出
     */
    private Moment getOwnedMoment(UUID momentId, UUID ownerUserId) {
        return getOwnedMoment(momentId, ownerUserId, false);
    }

    /**
     * 按 ID 查瞬间，并校验归属权（可选是否包含已删除的）。
     *
     * <p>流程：按开关查库拿瞬间 → 没查到记 debug 日志并抛未找到异常 → 作者不是当前用户记 warn 日志并抛无权限异常 → 返回瞬间。
     * 只读查询，无副作用。</p>
     *
     * @param momentId       瞬间 ID
     * @param ownerUserId    当前用户 ID
     * @param includeDeleted 是否包含已逻辑删除的瞬间（{@code true} 包含，给删除流程识别重复删除用；{@code false} 只查正常状态）
     * @return 校验通过的瞬间实体
     * @throws BusinessException 瞬间不存在（{@code MOMENT_NOT_FOUND}）或非本人所有时抛出
     */
    private Moment getOwnedMoment(UUID momentId, UUID ownerUserId, boolean includeDeleted) {
        Moment moment;
        if (includeDeleted) {
            moment = momentMapper.selectIncludingDeletedById(momentId);
        } else {
            moment = momentMapper.selectById(momentId);
        }
        if (moment == null) {
            log.debug("瞬间查询未命中，userId={}", ownerUserId);
            throw BusinessException.notFound("MOMENT_NOT_FOUND", "瞬间不存在");
        }
        if (!ownerUserId.equals(moment.getAuthorUserId())) {
            log.warn("瞬间访问被拒绝，momentId={}，userId={}，reason=非本人瞬间", moment.getId(), ownerUserId);
            throw BusinessException.forbidden("仅作者可以操作此瞬间");
        }
        return moment;
    }

    /**
     * 批量读取本人有效瞬间的 ID、作者和同行归属，任一瞬间无效则整批报错。
     */
    public List<Moment> getOwnedMoments(List<UUID> momentIds, UUID ownerUserId) {
        Assert.notEmpty(momentIds, "至少传入一个 momentId");

        List<UUID> ids = momentIds.stream().distinct().toList();
        List<Moment> moments = momentMapper.selectByUserIdAndIds(ownerUserId, ids);

        if (moments.size() != ids.size()) {
            throw BusinessException.invalidArgument("部分瞬间不存在、已删除或不属于本人");
        }
        return moments;
    }

    /**
     * 校验这批瞬间全是本人的、没删的，全对才放行。
     *
     * <p>流程：列表非空检查 → 数出列表里属于本人且未删除的条数 → 和列表长度比对，对不上整体报错，不指认具体哪个 ID。
     * 只读查询，无副作用。</p>
     *
     * @param momentIds   要检查的瞬间 ID 列表（重复 ID 内部去重）
     * @param ownerUserId 当前用户 ID
     * @throws IllegalArgumentException momentIds 为 null 或空时抛出
     * @throws BusinessException        有瞬间不存在、已删除或不属于本人时抛出（{@code INVALID_ARGUMENT}）
     */
    public void validateOwnedMoments(List<UUID> momentIds, UUID ownerUserId) {
        // 同一份校验复用 getOwnedMoments，调用方只要放行、不需要实体时丢弃返回值。
        getOwnedMoments(momentIds, ownerUserId);
    }

    /**
     * 把请求里的可编辑字段写进实体并更新数据库，空白标题存 null，地点传 null 时清空。
     * 顺手把保存值回填进传入实体，供调用方直接组装响应；不碰图片和标签关联。
     *
     * <p>副作用：更新 moment 表的可编辑字段，跟随外层事务。</p>
     *
     * @param moment     已确认归属的有效瞬间，本方法直接改它
     * @param request    全量编辑请求
     * @param memoryTime 发生时间，UTC Instant
     * @param location   已校验的地点，null 清空全部地点字段
     * @param savedAt    本次修改时间，UTC，写入 updatedAt
     */
    private void updateMoment(Moment moment,
                              UpdateMomentRequest request,
                              Instant memoryTime,
                              MomentLocation location,
                              Instant savedAt) {
        moment.setTitle(StringUtils.hasText(request.title()) ? request.title() : null);
        moment.setBody(request.body());
        moment.setMood(request.mood());
        moment.setMemoryTime(memoryTime);
        moment.setUpdatedAt(savedAt);
        fillLocation(moment, location);
        momentMapper.updateContentById(moment);
    }


    /**
     * 检查图文够不够发布：正文和图片至少有一个，正文不超长，图片不重复。
     *
     * <p>纯校验，不改入参，无副作用。</p>
     *
     * @param body    正文，可空，纯空白视为没写
     * @param fileIds 已解析的图片 ID，非 null，可空列表
     * @throws BusinessException 图文全空、正文超 UTF-8 字节上限或图片 ID 重复时抛出（400）
     */
    private void validateMomentContent(String body, List<UUID> fileIds) {
        if (!StringUtils.hasText(body) && fileIds.isEmpty()) {
            log.debug("瞬间内容校验失败，reason=正文和图片均为空");
            throw BusinessException.invalidArgument("请填写正文或添加图片");
        }
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            log.debug("瞬间内容校验失败，reason=正文超出字节上限，maxBytes={}", MAX_BODY_BYTES);
            throw BusinessException.invalidArgument("正文超出可保存长度");
        }
        if (new HashSet<>(fileIds).size() != fileIds.size()) {
            log.debug("瞬间内容校验失败，reason=图片ID重复，imageCount={}", fileIds.size());
            throw BusinessException.invalidArgument("同一图片不能重复添加");
        }
    }

    /**
     * 整理标签：去首尾空白、丢空白项、按首次出现去重，再校验长度。
     *
     * <p>纯校验整理，不改入参，无副作用。注意：Java 去重大小写敏感，数据库唯一索引按排序规则可能判等价，
     * 等价重复由入库层兜底（保留首次写法）。</p>
     *
     * @param tags 标签列表，注意非 null
     * @return 整理后的新列表，保持首次出现顺序
     * @throws BusinessException 任一标签超长时抛出（400）
     */
    private List<String> validateTags(List<String> tags) {
        Objects.requireNonNull(tags, "tags 不能为 null");
        List<String> normalized = tags.stream().map(String::strip)
                .filter(StringUtils::hasText).distinct().toList();
        for (String tag : normalized) {
            if (TextUtils.countCodePoints(tag) > MAX_TAG_LENGTH) {
                log.debug("瞬间标签校验失败，reason=标签超出长度上限，maxLength={}", MAX_TAG_LENGTH);
                throw BusinessException.invalidArgument("标签不能超过 " + MAX_TAG_LENGTH + " 字符");
            }
        }
        return normalized;
    }

    // TODO 发生时间缺范围校验：年份 1000～9999 的校验随 toDatabaseMemoryTime 删除而失效，
    //  超范围发生时间会直打数据库变 500，需在 create/update 入口补 400 参数校验

    /**
     * 校验并整理地点：经纬度必须成对填，允许只填名称和地址，坐标四舍五入到 7 位小数。
     *
     * <p>不改入参，无副作用。</p>
     *
     * @param location 请求里的地点，null 合法
     * @return 整理后的新地点对象；输入 null 返回 null；只填名称地址时坐标为 null
     * @throws BusinessException 经纬度只填了一项时抛出（400）
     */
    private MomentLocation validateLocation(MomentLocation location) {
        if (location == null) {
            return null;
        }
        if ((location.latitude() == null) != (location.longitude() == null)) {
            log.debug("瞬间地点校验失败，reason=经纬度未成对填写");
            throw BusinessException.invalidArgument("经度和纬度必须同时填写");
        }
        if (location.latitude() == null) {
            return new MomentLocation(null, null, location.name(), location.address());
        }
        return new MomentLocation(
                location.latitude().setScale(COORDINATE_SCALE, RoundingMode.HALF_UP),
                location.longitude().setScale(COORDINATE_SCALE, RoundingMode.HALF_UP),
                location.name(), location.address());
    }

    /**
     * 组装并插入新瞬间主记录，空白标题存 null；要求字段校验调用方已做完。
     *
     * <p>副作用：往 moment 表插一行，跟随外层事务。</p>
     *
     * @param request    已校验的发布请求（含客户端生成的瞬间 ID）
     * @param userId     作者 ID
     * @param memoryTime 发生时间，UTC Instant
     * @param location   已整理的地点，null 表示不带地点
     * @param savedAt    本次写入时间，UTC，同时给 createdAt 和 updatedAt
     * @return 插入后的瞬间实体
     * @throws BusinessException 瞬间 ID 被占时抛出（409，已软删除的记录也占 ID）
     */
    private Moment saveNewMoment(
            CreateMomentRequest request,
            UUID userId,
            Instant memoryTime,
            MomentLocation location,
            Instant savedAt) {
        Moment moment = new Moment();
        moment.setId(request.id());
        moment.setAuthorUserId(userId);
        moment.setGatheringId(request.gatheringId());
        moment.setTitle(StringUtils.hasText(request.title()) ? request.title() : null);
        moment.setBody(request.body());
        moment.setMood(request.mood());
        moment.setMemoryTime(memoryTime);
        fillLocation(moment, location);
        moment.setCreatedAt(savedAt);
        moment.setUpdatedAt(savedAt);
        try {
            momentMapper.insert(moment);
        } catch (DuplicateKeyException exception) {
            log.info("发布瞬间被拒绝，momentId={}，userId={}，reason=瞬间ID已被使用", request.id(), userId);
            throw BusinessException.conflict("MOMENT_ID_CONFLICT", "瞬间 ID 已被使用", exception);
        }
        return moment;
    }

    /**
     * 按提交顺序给新瞬间批量插图片关联，展示序号从 1 开始。
     *
     * <p>副作用：往 moment_image 表插数据，跟随外层事务。</p>
     *
     * @param momentId 新建瞬间的 ID
     * @param fileIds  已确认本人可用的图片 ID，可空列表
     * @param savedAt  本次写入时间，UTC
     */
    private void saveNewMomentImages(UUID momentId, List<UUID> fileIds, Instant savedAt) {
        List<MomentImage> images = new ArrayList<>();
        for (int index = 0; index < fileIds.size(); index++) {
            MomentImage image = new MomentImage();
            image.setId(UuidCreator.getTimeOrderedEpoch());
            image.setMomentId(momentId);
            image.setFileId(fileIds.get(index));
            image.setSortOrder(index + 1);
            image.setCreatedAt(savedAt);
            image.setUpdatedAt(savedAt);
            images.add(image);
        }
        momentImageMapper.insert(images);
    }

    /**
     * 给新瞬间逐条插标签；数据库判等价的重复标签跳过，保留首次填写的写法。
     *
     * <p>副作用：往 moment_tag 表插数据，跟随外层事务。</p>
     *
     * @param momentId 新建瞬间的 ID
     * @param tags     已整理的标签名称，可空列表
     * @param savedAt  本次写入时间，UTC
     * @return 实际插入的标签名称，按提交顺序
     */
    private List<String> saveNewMomentTags(UUID momentId, List<String> tags, Instant savedAt) {
        List<String> savedTags = new ArrayList<>(tags.size());
        for (String name : tags) {
            MomentTag tag = newTag(momentId, name, savedAt);
            // TODO 这个神秘的问题，为什么不用唯一约束？以及为什么不批量？
            if (momentTagMapper.insertIfAbsent(tag)) {
                savedTags.add(name);
            }
        }
        return savedTags;
    }

    /**
     * 把地点四字段写进实体，location 为 null 就全清空；直接改传入实体，不碰数据库。
     *
     * @param moment   待填的瞬间实体
     * @param location 已校验的地点，null 表示清空
     */
    private void fillLocation(Moment moment, MomentLocation location) {
        moment.setLatitude(location == null ? null : location.latitude());
        moment.setLongitude(location == null ? null : location.longitude());
        moment.setLocationName(location == null ? null : location.name());
        moment.setLocationAddress(location == null ? null : location.address());
    }

    /**
     * 对比全部历史图片关联与目标列表：新增不存在的、恢复已删除的、调整保留项顺序、软删除移除项。
     *
     * @param momentId 当前有效瞬间 ID
     * @param fileIds  已校验归属且不重复的目标图片 ID，空列表移除全部有效关联
     * @param savedAt  本次修改时间，UTC；历史关联 ID 和创建时间保留
     */
    private void syncMomentImages(UUID momentId, List<UUID> fileIds, Instant savedAt) {
        // TODO：将新增、恢复、调整顺序、移除项分类收集后批量执行，减少逐条写库。
        List<MomentImage> existing = momentImageMapper.selectIncludingDeletedByMomentId(momentId);
        Map<UUID, MomentImage> byFileId = existing.stream()
                .collect(Collectors.toMap(MomentImage::getFileId, image -> image));
        for (int index = 0; index < fileIds.size(); index++) {
            UUID fileId = fileIds.get(index);
            int sortOrder = index + 1;
            MomentImage image = byFileId.get(fileId);
            if (image == null) {
                image = new MomentImage();
                image.setId(UuidCreator.getTimeOrderedEpoch());
                image.setMomentId(momentId);
                image.setFileId(fileId);
                image.setSortOrder(sortOrder);
                image.setCreatedAt(savedAt);
                image.setUpdatedAt(savedAt);
                momentImageMapper.insert(image);
            } else if (image.getDeletedAt() != null) {
                image.setSortOrder(sortOrder);
                image.setUpdatedAt(savedAt);
                momentImageMapper.restore(image);
            } else if (image.getSortOrder() != sortOrder) {
                momentImageMapper.updateOrder(image.getId(), sortOrder, savedAt);
            }
        }
        var retained = new HashSet<>(fileIds);
        for (MomentImage image : existing) {
            if (image.getDeletedAt() == null && !retained.contains(image.getFileId())) {
                momentImageMapper.softDeleteById(image.getId(), savedAt);
            }
        }
    }

    /**
     * 按名称同步标签：新增、恢复、保留或软删除。名称匹配交给数据库，遵循唯一索引的比较规则。
     *
     * @param momentId 当前有效瞬间 ID
     * @param names    已去空白、去重并校验长度的目标名称，空列表清空有效标签
     * @param savedAt  本次修改时间，UTC；恢复时保留历史 ID 和创建时间
     */
    private void syncMomentTags(UUID momentId, List<String> names, Instant savedAt) {
        // TODO：批量匹配名称并收集新增、恢复、移除项后批量写入，保持与唯一索引相同的比较规则。
        List<MomentTag> existing = momentTagMapper.selectActive(momentId);
        var retained = new HashSet<UUID>();
        for (String name : names) {
            MomentTag tag = momentTagMapper.selectIncludingDeletedByName(momentId, name);
            if (tag == null) {
                tag = newTag(momentId, name, savedAt);
                momentTagMapper.insert(tag);
            } else if (retained.contains(tag.getId())) {
                // 本次提交中数据库判定等价的名称，保留第一次出现的写法。
                continue;
            } else if (tag.getDeletedAt() != null) {
                tag.setTagName(name);
                tag.setUpdatedAt(savedAt);
                momentTagMapper.restore(tag);
            }
            retained.add(tag.getId());
        }
        for (MomentTag tag : existing) {
            if (!retained.contains(tag.getId())) {
                momentTagMapper.softDelete(tag.getId(), savedAt);
            }
        }
    }


    /**
     * 创建待保存的瞬间标签关联。
     *
     * @param momentId 所属瞬间 ID
     * @param name     已整理的标签名称
     * @param savedAt  本次写入的 UTC 毫秒时间
     * @return 填写 UUIDv7 及审计字段的标签实体；实际插入或恢复由调用方选择
     */
    private MomentTag newTag(UUID momentId, String name, Instant savedAt) {
        MomentTag tag = new MomentTag();
        tag.setId(UuidCreator.getTimeOrderedEpoch());
        tag.setMomentId(momentId);
        tag.setTagName(name);
        tag.setCreatedAt(savedAt);
        tag.setUpdatedAt(savedAt);
        return tag;
    }


    /**
     * 浏览有效同行成员可读的瞬间时间线，按发生时间和 ID 正序。
     *
     * @param gatheringId 目标同行 ID
     * @param request     已校验的分页参数
     * @param userId      当前认证用户 ID
     * @return 同行瞬间摘要页，作者资料查不到时为 null
     */
    @Transactional(readOnly = true)
    public ResultPage<MomentSummaryResponse, GatheringMomentCursor> browseGatheringMoments(
            UUID gatheringId, PageRequest<GatheringMomentCursor> request, UUID userId) {
        // 1. 鉴权，用户必须在改同行里
        gatheringAccessService.getMemberGathering(gatheringId, userId);
        // 2. 分页查询
        GatheringMomentCursor cursor = request.cursor();
        Instant cursorTime = cursor == null ? null : cursor.memoryTime();
        UUID cursorId = cursor == null ? null : cursor.id();
        List<Moment> rows = momentMapper.selectPageAfterCursorByGatheringId(gatheringId, cursorTime, cursorId, request.limit() + 1);
        if (rows.isEmpty()){
            log.debug("浏览同行瞬间，gatheringId={}，userId={}，count={}，hasMore={}", gatheringId, userId, 0, false);
            return ResultPage.empty();
        }
        // 3. 构造响应
        boolean hasMore = rows.size() > request.limit();
        List<Moment> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        List<MomentSummaryResponse> momentSummaryResponses = convertToSummaryResponse(page);
        log.debug("浏览同行瞬间，gatheringId={}，userId={}，count={}，hasMore={}", gatheringId, userId, momentSummaryResponses.size(), hasMore);
        return ResultPage.toResultPage(
                momentSummaryResponses,
                hasMore,
                last->new GatheringMomentCursor(last.memoryTime(),last.id()));
    }



    /**
     * 经同行入口读取共享详情，成员资格和当前关联均须有效。
     *
     * @param gatheringId 入口同行 ID
     * @param momentId    瞬间 ID，即使为本人所写也检查当前同行关联
     * @param userId      当前认证用户 ID
     * @return 当前完整内容、图片及作者资料
     */
    @Transactional(readOnly = true)
    public MomentDetailedResponse getGatheringMoment(UUID gatheringId, UUID momentId, UUID userId) {
        // 1. 鉴权，用户必须在同行里
        gatheringAccessService.getMemberGathering(gatheringId, userId);
        Moment moment = getMoment(momentId);
        // 2. 鉴权，moment必须也在同行里
        if (!gatheringId.equals(moment.getGatheringId())) {
            throw BusinessException.notFound("MOMENT_NOT_FOUND", "瞬间不存在、已删除或已移出当前同行");
        }
        // 3. 构造响应
        MomentDetailedResponse response = convertToDetailedResponse(moment);
        log.debug("查看同行瞬间，gatheringId={}，momentId={}，userId={}", gatheringId, momentId, userId);
        return response;
    }


    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------
    // ----------------------------------------------------

    /**
     * 为已通过访问检查的瞬间批量加载图片、标签和作者资料，返回完整详情。调用方负责检查权限。
     *
     * @param page 已经检查访问权限的瞬间列表，可以为空
     * @return 按输入顺序返回瞬间详情，找不到作者资料时该字段为 null
     */
    public List<MomentDetailedResponse> loadAuthorizedResponses(List<Moment> page) {
        // 1. 空列表直接返回，避免再查图片、标签和作者。
        if (page.isEmpty()) {
            return List.of();
        }
        // 2. 批量签好可用图片的读取地址，保留每条瞬间原有的图片顺序。
        List<UUID> ids = page.stream().map(Moment::getId).toList();
        List<MomentImage> images = momentImageMapper.selectFeedImages(ids);
        Map<UUID, String> imageUrls = mediaService.getReadUrls(
                images.stream().map(MomentImage::getFileId).distinct().toList());
        Map<UUID, List<UUID>> fileIdsByMoment = images.stream()
                .filter(image -> imageUrls.containsKey(image.getFileId()))
                .collect(Collectors.groupingBy(MomentImage::getMomentId,
                        Collectors.mapping(MomentImage::getFileId, Collectors.toList())));
        // 3. 批量读取标签和作者资料，按瞬间详情的格式组装结果。
        Map<UUID, List<String>> tagsByMoment = momentTagMapper.selectPageTags(ids).stream()
                .collect(Collectors.groupingBy(MomentTag::getMomentId,
                        Collectors.mapping(MomentTag::getTagName, Collectors.toList())));
        Map<UUID, PublicUserResponse> authors = loadAuthors(page);
        return page.stream().map(moment -> toResponse(moment,
                toImageResponses(fileIdsByMoment.getOrDefault(moment.getId(), List.of()), imageUrls),
                tagsByMoment.getOrDefault(moment.getId(), List.of()), authors.get(moment.getAuthorUserId()))).toList();
    }

    /**
     * 按同行时间线首屏或后续页读取摘要投影，调用方已确认成员资格。
     */
    private List<Moment> queryGatheringMoments(UUID gatheringId, PageRequest<GatheringMomentCursor> request) {
        // 将游标转换为数据库 UTC 时间，首屏不提供边界。
        GatheringMomentCursor cursor = request.cursor();
        Instant cursorTime = cursor == null ? null : cursor.memoryTime();
        UUID cursorId = cursor == null ? null : cursor.id();
        return momentMapper.selectPageAfterCursorByGatheringId(gatheringId, cursorTime, cursorId, request.limit() + 1);
    }

    /**
     * 为已授权的摘要投影批量加载图片与作者，空页直接结束，保持输入顺序。
     */
    public List<MomentSummaryResponse> loadSummaries(List<Moment> page) {
        // 只处理返回页，额外记录不查询图片和作者资料。
        if (page.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<String>> imageUrls = loadFeedImageUrls(page);
        Map<UUID, PublicUserResponse> authors = loadAuthors(page);
        return page.stream().map(moment -> toFeedItem(
                moment,
                imageUrls.getOrDefault(moment.getId(), List.of()),
                authors.get(moment.getAuthorUserId()))
        ).toList();
    }

    /**
     * 按瞬间作者去重批量加载公开资料，缺失资料由响应映射为 null。
     */
    private Map<UUID, PublicUserResponse> loadAuthors(List<Moment> moments) {
        // 作者来自已读取的瞬间，不根据当前查看者推断。
        return userService.getPublicUsers(moments.stream().map(Moment::getAuthorUserId).distinct().toList());
    }

    /**
     * 批量读取本页图片关联并签好读取地址，按瞬间分组，保留展示顺序；首张地址用作封面。
     * 失效文件由签名层记 ERROR 后省略。
     *
     * @param page 实际返回的非空瞬间列表，不含额外记录
     * @return 瞬间 ID 到有序读取地址列表的映射；无有效图片的瞬间不在映射中
     */
    private Map<UUID, List<String>> loadFeedImageUrls(List<Moment> page) {
        List<MomentImage> images = momentImageMapper.selectFeedImages(page.stream().map(Moment::getId).toList());
        var imageUrls = mediaService.getReadUrls(
                images.stream().map(MomentImage::getFileId).distinct().toList());
        return images.stream().filter(image -> imageUrls.containsKey(image.getFileId()))
                .collect(Collectors.groupingBy(MomentImage::getMomentId,
                        Collectors.mapping(image -> imageUrls.get(image.getFileId()), Collectors.toList())));
    }

    /**
     * 组装摘要卡片，首张有效图片地址作封面，无有效图片时封面为 null、图片数为 0。
     *
     * @param moment    含 ID、作者、标题、正文、心情和发生时间的摘要实体
     * @param author    已加载的作者公开资料，查不到时为 null
     * @param imageUrls 已签名的有效图片地址，按展示顺序排列，允许为空
     * @return 卡片摘要；本方法不查库
     */
    private MomentSummaryResponse toFeedItem(Moment moment, List<String> imageUrls, PublicUserResponse author) {
        String coverUrl = imageUrls.isEmpty() ? null : imageUrls.get(0);
        return new MomentSummaryResponse(moment.getId(), moment.getTitle(), bodyPreview(moment.getBody()),
                coverUrl, imageUrls.size(), moment.getMood(), moment.getMemoryTime(), author);
    }

    /**
     * 以数据库实体为准组装详情，地点四个字段全部为空时返回 null。
     *
     * @param moment 已保存或查询到的完整瞬间实体，时间字段为 UTC
     * @param images 已生成读取地址且按展示顺序排列的图片列表，可为空列表
     * @param author 已加载的作者公开资料，查不到时为 null
     * @param tags   实际保存或查询到的标签名称，可为空列表
     * @return 独立的详情响应，时间为 UTC Instant；本方法不访问数据库或修改实体
     */
    private MomentDetailedResponse toResponse(
            Moment moment, List<MomentDetailedResponse.Image> images, List<String> tags, PublicUserResponse author) {
        MomentLocation location = moment.getLatitude() == null && moment.getLongitude() == null
                && moment.getLocationName() == null && moment.getLocationAddress() == null ? null
                : new MomentLocation(moment.getLatitude(), moment.getLongitude(),
                moment.getLocationName(), moment.getLocationAddress());
        return new MomentDetailedResponse(moment.getId(), moment.getTitle(), moment.getBody(),
                images, tags, moment.getMood(), moment.getMemoryTime(), location,
                moment.getCreatedAt(), moment.getUpdatedAt(), author);
    }

    /**
     * 为已校验图片生成响应，沿用提交顺序和批量签好的读取地址，本方法不访问数据库。
     *
     * @param fileIds   有序图片 ID，允许为空列表
     * @param imageUrls 每个图片 ID 对应的已签名读取地址，调用方已校验或过滤，保证地址齐全
     * @return 图片响应列表，空输入返回空列表
     */
    private List<MomentDetailedResponse.Image> toImageResponses(List<UUID> fileIds, Map<UUID, String> imageUrls) {
        return fileIds.stream().map(fileId -> new MomentDetailedResponse.Image(
                fileId, imageUrls.get(fileId))).toList();
    }

    /**
     * 截取正文开头最多 100 个 Unicode 码点，保留原有文本。
     *
     * @param body 正文，可空
     * @return 正文摘要，null 或空字符串返回 null
     */
    private String bodyPreview(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        int end = body.offsetByCodePoints(0, Math.min(100, body.codePointCount(0, body.length())));
        return body.substring(0, end);
    }
}
