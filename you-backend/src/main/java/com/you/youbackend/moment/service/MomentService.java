package com.you.youbackend.moment.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.entity.MomentImage;
import com.you.youbackend.moment.entity.MomentTag;
import com.you.youbackend.moment.mapper.MomentImageMapper;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.mapper.MomentTagMapper;
import com.you.youbackend.moment.model.MomentFeedCursor;
import com.you.youbackend.moment.model.MomentLocation;
import com.you.youbackend.moment.request.CreateMomentRequest;
import com.you.youbackend.moment.request.UpdateMomentRequest;
import com.you.youbackend.moment.response.MomentFeedItem;
import com.you.youbackend.moment.response.MomentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    /**
     * 分页浏览本人瞬间：按游标查询、批量加载有效图片、组装卡片和分页结果。
     * 按发生时间和 ID 倒序，多查一条判断下一页，额外记录不加载图片。
     * @param userId 当前登录用户 ID，查询仅限本人的有效瞬间
     * @param request 已校验的分页请求，游标为空表示首屏
     * @return 摘要列表及下一页游标；空页或末页游标为 null
     */
    @Transactional(readOnly = true)
    public ResultPage<MomentFeedItem, MomentFeedCursor> browseYours(
            UUID userId, PageRequest<MomentFeedCursor> request) {
        List<Moment> rows = queryFeedMoments(userId, request);
        if (rows.isEmpty()) {
            log.debug("浏览本人瞬间，userId={}，limit={}，itemCount=0，hasMore=false", userId, request.limit());
            return new ResultPage<>(List.of(), null, false);
        }
        boolean hasMore = rows.size() > request.limit();
        List<Moment> page = rows.subList(0, Math.min(request.limit(), rows.size()));
        Map<UUID, List<String>> imageKeysByMoment = loadFeedImageKeys(userId, page);
        List<MomentFeedItem> items = page.stream()
                .map(moment -> toFeedItem(moment, imageKeysByMoment.getOrDefault(moment.getId(), List.of())))
                .toList();
        log.debug("浏览本人瞬间，userId={}，limit={}，itemCount={}，hasMore={}",
                userId, request.limit(), items.size(), hasMore);
        return toFeedPage(items, hasMore);
    }

    /**
     * 发布客户端草稿，关联已直传并登记就绪的本人图片，返回完整详情。
     *
     * <p>1. 校验图文至少一种及图片不重复，整理标签、发生时间和地点，批量确认图片可用。
     * 2. 保存瞬间主记录，再按提交顺序保存图片关联、保存去重后的标签。
     * 3. 生成图片临时读取地址，组装详情并记录写入日志。
     * 主记录和关联在同一事务中保存，任一步骤失败均回滚。</p>
     *
     * @param request 已由 MVC 完成 UUIDv7、字段格式及必填校验的发布请求
     * @param userId 当前登录用户 ID，作为瞬间作者和图片归属校验依据
     * @return 已保存的瞬间详情，图片顺序与提交顺序一致
     * @throws BusinessException 图文内容不符合要求、图片不可用、发生时间超出范围，或瞬间 ID 已被使用
     */
    @Transactional
    public MomentResponse createMoment(CreateMomentRequest request, UUID userId) {
        List<UUID> fileIds = request.imageFileIds() == null ? List.of() : request.imageFileIds();
        validateContent(request.body(), fileIds);
        List<String> tags = prepareTags(request.tags());
        MomentLocation location = normalizeLocation(request.location());
        LocalDateTime memoryTime = toDatabaseMemoryTime(request.memoryTime());
        Map<UUID, String> imageKeys = requireOwnedImageKeys(userId, fileIds);
        LocalDateTime savedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

        Moment moment = saveNewMoment(request, userId, memoryTime, location, savedAt);
        saveNewImages(moment.getId(), fileIds, savedAt);
        List<String> savedTags = saveNewTags(moment.getId(), tags, savedAt);

        List<MomentResponse.Image> images = toImageResponses(fileIds, imageKeys);
        log.info("已写入瞬间，momentId={}，userId={}，imageCount={}，tagCount={}",
                moment.getId(), userId, images.size(), savedTags.size());
        return toResponse(moment, images, savedTags);
    }

    /**
     * 全量保存本人瞬间：校验归属和完整请求，更新主记录，再分别同步图片、标签关联，返回详情。
     * 空数组移除全部对应关联，null 清空可空字段。关联保留软删除历史，媒体文件保留；失败整体回滚。
     *
     * @param id 待编辑瞬间 ID
     * @param request 已由 MVC 校验必填字段的全量请求
     * @param userId 当前登录用户 ID
     * @return 更新后的完整详情，图片按本次提交顺序返回
     * @throws BusinessException 瞬间不存在、非本人，或内容、图片不符合要求
     */
    @Transactional
    public MomentResponse updateMoment(UUID id, UpdateMomentRequest request, UUID userId) {
        Moment moment = requireOwnedMoment(momentMapper.selectById(id), userId);
        List<UUID> fileIds = request.imageFileIds();
        validateContent(request.body(), fileIds);
        List<String> tags = prepareTags(request.tags());
        MomentLocation location = normalizeLocation(request.location());
        LocalDateTime memoryTime = toDatabaseMemoryTime(request.memoryTime());
        Map<UUID, String> imageKeys = requireOwnedImageKeys(userId, fileIds);
        LocalDateTime savedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

        updateMomentContent(moment, request, memoryTime, location, savedAt);
        syncMomentImages(id, fileIds, savedAt);
        syncMomentTags(id, tags, savedAt);

        List<MomentResponse.Image> images = toImageResponses(fileIds, imageKeys);
        List<String> savedTags = momentTagMapper.selectNames(id);
        log.info("已修改瞬间，momentId={}，userId={}，imageCount={}，tagCount={}",
                id, userId, images.size(), savedTags.size());
        return toResponse(moment, images, savedTags);
    }

    /**
     * 查看本人瞬间详情，供阅读和编辑回填使用。
     * 先查询并校验作者归属，再加载有效图片和标签，最后组装详情；图片按展示顺序返回，失效图片跳过。
     *
     * @param id 瞬间 ID，查询由 MyBatis-Plus 自动排除软删除记录
     * @param userId 当前登录用户 ID
     * @return 完整详情，图片附带临时读取地址，时间统一为 UTC
     * @throws BusinessException 瞬间不存在或不属于当前用户
     */
    @Transactional(readOnly = true)
    public MomentResponse getMoment(UUID id, UUID userId) {
        Moment moment = requireOwnedMoment(momentMapper.selectById(id), userId);
        List<MomentResponse.Image> images = loadMomentImages(id, userId);
        List<String> tags = momentTagMapper.selectNames(id);
        log.debug("查询瞬间详情，momentId={}，userId={}，imageCount={}，tagCount={}",
                id, userId, images.size(), tags.size());
        return toResponse(moment, images, tags);
    }

    /**
     * 删除本人瞬间：查询并校验作者，已删除时直接返回；随后软删除主记录、图片和标签关联。
     * 全部写入在同一事务内完成，媒体文件保留。
     *
     * @param id 瞬间 ID
     * @param userId 当前登录用户 ID
     * @throws BusinessException 瞬间不存在或不属于当前用户
     */
    @Transactional
    public void deleteMoment(UUID id, UUID userId) {
        Moment moment = requireOwnedMoment(momentMapper.selectIncludingDeleted(id), userId);
        if (moment.getDeletedAt() != null) {
            log.debug("瞬间已删除，跳过重复操作，momentId={}，userId={}", id, userId);
            return;
        }

        LocalDateTime deletedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        momentMapper.softDelete(id, deletedAt);
        momentImageMapper.removeAssociations(id, deletedAt);
        momentTagMapper.removeAssociations(id, deletedAt);
        log.info("已删除瞬间，momentId={}，userId={}", id, userId);
    }

    /**
     * 将游标转换为数据库 UTC 时间，查询实际页大小加一条记录。
     * @param userId 当前用户 ID
     * @param request 已校验的分页请求
     * @return 按发生时间、ID 倒序的摘要数据，额外记录供判断下一页
     */
    private List<Moment> queryFeedMoments(UUID userId, PageRequest<MomentFeedCursor> request) {
        MomentFeedCursor cursor = request.cursor();
        LocalDateTime cursorTime = (cursor == null) ? null : toDatabaseMemoryTime(cursor.memoryTime());
        UUID cursorId = (cursor == null) ? null : cursor.id();
        // 多查一条判断是否还有下一页，图片只按实际返回的记录批量查询。
        return momentMapper.selectFeed(userId, cursorTime, cursorId, request.limit() + 1);
    }

    /**
     * 批量读取本页图片关联及有效文件，按瞬间分组，保留展示顺序；首个 key 用作封面。
     * @param userId 当前用户 ID，用于校验文件归属
     * @param page 实际返回的非空瞬间列表，不含额外记录
     * @return 瞬间 ID 到有序有效图片 key 列表的映射；无有效图片的瞬间不在映射中
     */
    private Map<UUID, List<String>> loadFeedImageKeys(UUID userId, List<Moment> page) {
        List<MomentImage> images = momentImageMapper.selectFeedImages(page.stream().map(Moment::getId).toList());
        var imageKeys = mediaService.findOwnedImageKeys(userId,
                images.stream().map(MomentImage::getFileId).distinct().toList());
        return images.stream().filter(image -> imageKeys.containsKey(image.getFileId()))
                .collect(Collectors.groupingBy(MomentImage::getMomentId,
                        Collectors.mapping(image -> imageKeys.get(image.getFileId()), Collectors.toList())));
    }

    /**
     * 组装摘要卡片，为首张有效图片签名，无有效图片时封面为 null、图片数为 0。
     * @param moment 仅含 ID、标题、正文、心情和发生时间的摘要实体
     * @param imageKeys 有效图片 key，按展示顺序排列，允许为空
     * @return 卡片摘要；本方法不查库
     */
    private MomentFeedItem toFeedItem(Moment moment, List<String> imageKeys) {
        String coverUrl = imageKeys.isEmpty() ? null : mediaService.createReadUrl(imageKeys.get(0));
        return new MomentFeedItem(moment.getId(), moment.getTitle(), bodyPreview(moment.getBody()),
                coverUrl, imageKeys.size(), moment.getMood(), moment.getMemoryTime().toInstant(ZoneOffset.UTC));
    }

    /**
     * 以实际返回的最后一条卡片作为下一页边界，组装分页响应。
     * @param items 按查询顺序排列的非空卡片列表
     * @param hasMore 是否多查到了额外记录
     * @return 分页结果，末页游标为 null；本方法不查库
     */
    private ResultPage<MomentFeedItem, MomentFeedCursor> toFeedPage(List<MomentFeedItem> items, boolean hasMore) {
        MomentFeedItem last = items.get(items.size() - 1);
        MomentFeedCursor nextCursor = hasMore ? new MomentFeedCursor(last.memoryTime(), last.id()) : null;
        return new ResultPage<>(items, nextCursor, hasMore);
    }

    /**
     * 截取正文开头最多 100 个 Unicode 码点，保留原有文本。
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

    /**
     * 检查查询结果存在且归当前用户所有，成功时返回原实体。
     *
     * @param moment Mapper 查询结果，可空；是否过滤软删除由调用方的查询决定
     * @param userId 当前登录用户 ID
     * @return 原瞬间实体
     * @throws BusinessException 记录不存在或作者与当前用户不一致
     */
    private Moment requireOwnedMoment(Moment moment, UUID userId) {
        if (moment == null) {
            log.debug("瞬间查询未命中，userId={}", userId);
            throw BusinessException.notFound("MOMENT_NOT_FOUND", "瞬间不存在");
        }
        if (!userId.equals(moment.getAuthorUserId())) {
            log.warn("瞬间访问被拒绝，momentId={}，userId={}，reason=非本人瞬间", moment.getId(), userId);
            throw BusinessException.forbidden("仅作者可以操作此瞬间");
        }
        return moment;
    }

    /**
     * 填写并保存可编辑字段，同时更新传入实体，供响应组装复用。
     *
     * @param moment 已确认归属的有效瞬间
     * @param request 已校验的完整编辑请求，空白标题转为 null
     * @param memoryTime 已整理为 UTC 毫秒精度的发生时间
     * @param location 已校验的地点，null 清空全部地点字段
     * @param savedAt 本次修改时间，UTC
     */
    private void updateMomentContent(Moment moment, UpdateMomentRequest request, LocalDateTime memoryTime,
                                     MomentLocation location, LocalDateTime savedAt) {
        moment.setTitle(StringUtils.hasText(request.title()) ? request.title() : null);
        moment.setBody(request.body());
        moment.setMood(request.mood());
        moment.setMemoryTime(memoryTime);
        moment.setUpdatedAt(savedAt);
        fillLocation(moment, location);
        momentMapper.updateContent(moment);
    }

    /**
     * 以数据库实体为准组装详情，地点四个字段全部为空时返回 null。
     *
     * @param moment 已保存或查询到的完整瞬间实体，时间字段为 UTC
     * @param images 已生成读取地址且按展示顺序排列的图片列表，可为空列表
     * @param tags 实际保存或查询到的标签名称，可为空列表
     * @return 独立的详情响应，时间转换为 Instant；本方法不访问数据库或修改实体
     */
    private MomentResponse toResponse(Moment moment, List<MomentResponse.Image> images, List<String> tags) {
        MomentLocation location = moment.getLatitude() == null && moment.getLongitude() == null
                && moment.getLocationName() == null && moment.getLocationAddress() == null ? null
                : new MomentLocation(moment.getLatitude(), moment.getLongitude(),
                        moment.getLocationName(), moment.getLocationAddress());
        return new MomentResponse(moment.getId(), moment.getTitle(), moment.getBody(),
                images, tags, moment.getMood(), moment.getMemoryTime().toInstant(ZoneOffset.UTC), location,
                moment.getCreatedAt().toInstant(ZoneOffset.UTC), moment.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    /**
     * 检查正文与图片是否满足发布要求。
     *
     * @param body 普通文本正文，可空，空白文本视为无有效正文
     * @param fileIds 已解析的图片 ID 列表，非 null，允许为空列表
     * @throws BusinessException 图文均为空、正文超过 UTF-8 字节上限或存在重复图片；本方法不修改入参
     */
    private void validateContent(String body, List<UUID> fileIds) {
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
     * 清理标签空白、校验长度并去除重复项。
     *
     * @param tags 可空列表，元素已通过 MVC 非 null 校验
     * @return 去首尾空白、过滤空白项并按首次出现顺序去除完全相同名称的新列表；null 转为空列表
     * @throws BusinessException 标签超过长度限制；数据库排序规则下的等价项由 Mapper 进一步去重
     * TODO：统一标签等价和去重规则；Java distinct 与数据库不区分大小写、重音的排序规则存在差异，需明确保留的写法及创建、修改的一致行为。
     */
    private List<String> prepareTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> normalized = tags.stream().map(String::strip)
                .filter(StringUtils::hasText).distinct().toList();
        for (String tag : normalized) {
            if (tag.codePointCount(0, tag.length()) > MAX_TAG_LENGTH) {
                log.debug("瞬间标签校验失败，reason=标签超出长度上限，maxLength={}", MAX_TAG_LENGTH);
                throw BusinessException.invalidArgument("标签不能超过 " + MAX_TAG_LENGTH + " 字符");
            }
        }
        return normalized;
    }

    /**
     * 将发生时间转换为数据库使用的 UTC 毫秒时间。
     *
     * @param value 已由请求校验保证非空的时间点；JSON 时间格式由 Jackson 解析
     * @return UTC 下的 LocalDateTime，截断至 DATETIME(3) 支持的毫秒精度
     * @throws BusinessException 时间超出 MySQL DATETIME 的 1000～9999 年范围
     */
    private LocalDateTime toDatabaseMemoryTime(Instant value) {
        LocalDateTime memoryTime;
        try {
            memoryTime = LocalDateTime.ofInstant(value, ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeException exception) {
            log.debug("瞬间发生时间校验失败，reason=无法转换为数据库时间");
            throw BusinessException.invalidArgument("发生时间超出可保存范围");
        }
        if (memoryTime.getYear() < 1000 || memoryTime.getYear() > 9999) {
            log.debug("瞬间发生时间校验失败，reason=超出数据库年份范围");
            throw BusinessException.invalidArgument("发生时间超出可保存范围");
        }
        return memoryTime;
    }

    /**
     * 校验经纬度成对填写，并统一坐标精度。
     *
     * @param location 请求中的地点，允许为空；允许仅填写地名和地址，经纬度需成对提供
     * @return 新的地点对象，坐标四舍五入至七位小数；输入为空时返回 null，不修改原对象
     * @throws BusinessException 经纬度仅填写其中一项
     */
    private MomentLocation normalizeLocation(MomentLocation location) {
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
     * 构造并插入新瞬间，空白标题保存为 null；校验由调用方提前完成。
     *
     * @param request 已通过字段和业务校验的发布请求
     * @param userId 当前作者 ID
     * @param memoryTime 已转换为数据库精度的 UTC 发生时间
     * @param location 已校验并整理坐标精度的地点，可空
     * @param savedAt 本次写入的创建和修改时间，UTC
     * @return 已插入的瞬间实体
     * @throws BusinessException 瞬间 ID 已存在，包括已软删除的记录
     */
    private Moment saveNewMoment(CreateMomentRequest request, UUID userId, LocalDateTime memoryTime,
                                 MomentLocation location, LocalDateTime savedAt) {
        Moment moment = new Moment();
        moment.setId(request.id());
        moment.setAuthorUserId(userId);
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
     * 按提交顺序构造并插入图片关联，展示序号从 1 开始；写入当前发布事务。
     *
     * @param momentId 本次新建的瞬间 ID
     * @param fileIds 已确认属于本人且可用的图片 ID，允许为空列表
     * @param savedAt 本次关联的创建和修改时间，UTC
     */
    private void saveNewImages(UUID momentId, List<UUID> fileIds, LocalDateTime savedAt) {
        // TODO：先收集图片关联，再批量插入，减少逐张图片写库的请求次数。
        for (int index = 0; index < fileIds.size(); index++) {
            MomentImage image = new MomentImage();
            image.setId(UuidCreator.getTimeOrderedEpoch());
            image.setMomentId(momentId);
            image.setFileId(fileIds.get(index));
            image.setSortOrder(index + 1);
            image.setCreatedAt(savedAt);
            image.setUpdatedAt(savedAt);
            momentImageMapper.insert(image);
        }
    }

    /**
     * 构造并保存新瞬间的标签，数据库判定等价的名称保留首次填写的写法。
     *
     * @param momentId 本次新建的瞬间 ID
     * @param tags 已去空白、去重并校验长度的标签名称，允许为空列表
     * @param savedAt 本次标签的创建和修改时间，UTC
     * @return 实际插入的标签名称，按提交顺序排列
     */
    private List<String> saveNewTags(UUID momentId, List<String> tags, LocalDateTime savedAt) {
        // TODO：在统一标签比较规则后，批量查重并插入，减少逐个标签查库、写库。
        List<String> savedTags = new ArrayList<>(tags.size());
        for (String name : tags) {
            MomentTag tag = newTag(momentId, name, savedAt);
            if (momentTagMapper.insertIfAbsent(tag)) {
                savedTags.add(name);
            }
        }
        return savedTags;
    }

    /**
     * 将整理后的地点写入实体，地点为 null 时清空全部地点字段；本方法不访问数据库。
     *
     * @param moment 待填充的瞬间实体，本方法直接修改该对象
     * @param location 已检查坐标成对且统一精度的地点，可空
     */
    private void fillLocation(Moment moment, MomentLocation location) {
        moment.setLatitude(location == null ? null : location.latitude());
        moment.setLongitude(location == null ? null : location.longitude());
        moment.setLocationName(location == null ? null : location.name());
        moment.setLocationAddress(location == null ? null : location.address());
    }

    /**
     * 批量读取本人有效图片的对象键，并确认提交的每张图片均可使用，供写入前校验。
     *
     * @param userId 当前用户 ID
     * @param fileIds 已校验不重复的图片 ID 列表，允许为空列表
     * @return 文件 ID 到对象键的映射，供后续签名复用；空列表返回空映射
     * @throws BusinessException 任一图片未登记、已删除、非本人上传或类型不支持
     */
    private Map<UUID, String> requireOwnedImageKeys(UUID userId, List<UUID> fileIds) {
        Map<UUID, String> keys = mediaService.findOwnedImageKeys(userId, fileIds);
        if (keys.size() != fileIds.size()) {
            log.warn("瞬间图片校验失败，userId={}，submittedCount={}，usableCount={}",
                    userId, fileIds.size(), keys.size());
            throw BusinessException.badRequest("FILE_NOT_USABLE", "请选择已上传完成的本人图片");
        }
        return keys;
    }

    /**
     * 对比全部历史图片关联与目标列表：新增不存在的、恢复已删除的、调整保留项顺序、软删除移除项。
     * @param momentId 当前有效瞬间 ID
     * @param fileIds 已校验归属且不重复的目标图片 ID，空列表移除全部有效关联
     * @param savedAt 本次修改时间，UTC；历史关联 ID 和创建时间保留
     */
    private void syncMomentImages(UUID momentId, List<UUID> fileIds, LocalDateTime savedAt) {
        // TODO：将新增、恢复、调整顺序、移除项分类收集后批量执行，减少逐条写库。
        List<MomentImage> existing = momentImageMapper.selectIncludingDeleted(momentId);
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
                momentImageMapper.softDelete(image.getId(), savedAt);
            }
        }
    }

    /**
     * 按名称同步标签：新增、恢复、保留或软删除。名称匹配交给数据库，遵循唯一索引的比较规则。
     *
     * @param momentId 当前有效瞬间 ID
     * @param names 已去空白、去重并校验长度的目标名称，空列表清空有效标签
     * @param savedAt 本次修改时间，UTC；恢复时保留历史 ID 和创建时间
     */
    private void syncMomentTags(UUID momentId, List<String> names, LocalDateTime savedAt) {
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
     * 加载详情图片：按展示顺序查询关联，批量取得有效文件的 key，再为有效图片生成读取地址。
     * 已删除、未登记或不属于作者的文件被过滤，保留其余图片的原有顺序。
     *
     * @param momentId 已确认归属的有效瞬间 ID
     * @param userId 瞬间作者 ID，用于查询本人可用图片
     * @return 有序图片响应；无关联或所有文件均失效时返回空列表
     */
    private List<MomentResponse.Image> loadMomentImages(UUID momentId, UUID userId) {
        List<UUID> fileIds = momentImageMapper.selectInDisplayOrder(momentId).stream()
                .map(MomentImage::getFileId).toList();
        if (fileIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> imageKeys = mediaService.findOwnedImageKeys(userId, fileIds);
        List<UUID> validFileIds = fileIds.stream().filter(imageKeys::containsKey).toList();
        if (validFileIds.size() != fileIds.size()) {
            log.debug("详情跳过失效图片，momentId={}，userId={}，skippedCount={}",
                    momentId, userId, fileIds.size() - validFileIds.size());
        }
        return toImageResponses(validFileIds, imageKeys);
    }

    /**
     * 为已校验图片生成响应，沿用提交顺序和批量查询得到的对象键，本方法不访问数据库。
     *
     * @param fileIds 有序图片 ID，允许为空列表
     * @param imageKeys 每个图片 ID 对应的对象键，调用方已校验或过滤，保证键齐全
     * @return 带临时读取地址的图片列表，空输入返回空列表
     */
    private List<MomentResponse.Image> toImageResponses(List<UUID> fileIds, Map<UUID, String> imageKeys) {
        return fileIds.stream().map(fileId -> new MomentResponse.Image(
                fileId, mediaService.createReadUrl(imageKeys.get(fileId)))).toList();
    }

    /**
     * 创建待保存的瞬间标签关联。
     *
     * @param momentId 所属瞬间 ID
     * @param name 已整理的标签名称
     * @param savedAt 本次写入的 UTC 毫秒时间
     * @return 填写 UUIDv7 及审计字段的标签实体；实际插入或恢复由调用方选择
     */
    private MomentTag newTag(UUID momentId, String name, LocalDateTime savedAt) {
        MomentTag tag = new MomentTag();
        tag.setId(UuidCreator.getTimeOrderedEpoch());
        tag.setMomentId(momentId);
        tag.setTagName(name);
        tag.setCreatedAt(savedAt);
        tag.setUpdatedAt(savedAt);
        return tag;
    }


}
