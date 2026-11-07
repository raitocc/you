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
import com.you.youbackend.moment.model.MomentMood;
import com.you.youbackend.moment.request.CreateMomentRequest;
import com.you.youbackend.moment.request.UpdateMomentRequest;
import com.you.youbackend.moment.response.MomentFeedItem;
import com.you.youbackend.moment.response.MomentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
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
import java.util.UUID;
import java.util.stream.Collectors;

// TODO：流程业务复杂的方法，应该说明业务流程，副作用
// TODO: 私有方法应该注明作用，入参出参，副作用，行为等
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
     * 分页读取本人瞬间，并为每张卡片补充有效图片数和封面。
     * TODO：拆分有效图片整理、单条卡片组装和分页结果组装，主方法保留查询流程编排；保留图片批量查询。
     * 1. 将游标时间转换为数据库 UTC 时间，按发生时间、ID 倒序多查一条，判断是否存在下一页。
     * 2. 批量查询图片，过滤失效文件，按展示顺序选择封面并组装摘要卡片。
     * 3. 有下一页时，以本页最后一条记录生成结构化游标；空页或末页游标为 null。
     *
     * @param userId 当前登录用户 ID，查询范围仅限本人有效瞬间
     * @param request 已由 MVC 校验的分页请求；游标为空表示首屏，limit 已补默认值
     * @return 分页卡片及下一页游标；无记录时 items 为空列表，hasMore 为 false
     */
    @Transactional(readOnly = true)
    public ResultPage<MomentFeedItem, MomentFeedCursor> browseYours(
            UUID userId, PageRequest<MomentFeedCursor> request) {
        int limit = request.limit();
        MomentFeedCursor cursor = request.cursor();
        LocalDateTime cursorTime = cursor == null ? null : toDatabaseMemoryTime(cursor.memoryTime());
        UUID cursorId = cursor == null ? null : cursor.id();
        // 多查一条判断是否还有下一页，图片只按实际返回的记录批量查询。
        List<Moment> rows = momentMapper.selectFeed(userId, cursorTime, cursorId, limit + 1);
        if (rows.isEmpty()) {
            return new ResultPage<>(List.of(), null, false);
        }
        boolean hasMore = rows.size() > limit;
        List<Moment> page = rows.subList(0, Math.min(limit, rows.size()));
        List<MomentImage> images = momentImageMapper.selectFeedImages(page.stream().map(Moment::getId).toList());
        var imageKeys = mediaService.findOwnedImageKeys(userId,
                images.stream().map(MomentImage::getFileId).distinct().toList());
        // 按展示顺序保留有效图片，首张作为封面，其余图片也计入总数。
        var imagesByMoment = images.stream()
                .filter(image -> imageKeys.containsKey(image.getFileId()))
                .collect(Collectors.groupingBy(MomentImage::getMomentId));

        // TODO：将此处图片查找、封面签名、正文摘要和时间转换提取为职责明确的卡片组装方法，说明首张有效图片作为封面的顺序约定。
        List<MomentFeedItem> items = page.stream().map(moment -> {
            List<MomentImage> validImages = imagesByMoment.getOrDefault(moment.getId(), List.of());
            String coverUrl = validImages.isEmpty() ? null
                    : mediaService.createReadUrl(imageKeys.get(validImages.get(0).getFileId()));
            return new MomentFeedItem(moment.getId(), moment.getTitle(), bodyPreview(moment.getBody()),
                    coverUrl, validImages.size(), moment.getMood(), moment.getMemoryTime().toInstant(ZoneOffset.UTC));
        }).toList();
        // 下一页从本页最后一条之后开始，多查的那条留到下一页。
        Moment last = page.get(page.size() - 1);
        MomentFeedCursor nextCursor = hasMore
                ? new MomentFeedCursor(last.getMemoryTime().toInstant(ZoneOffset.UTC), last.getId()) : null;
        return new ResultPage<>(items, nextCursor, hasMore);
    }

    /**
     * 保留正文开头最多 100 个 Unicode 字符。
     * TODO：补充入参和返回值注释：正文允许为 null，null 或空字符串返回 null，截取按 Unicode 码点计算并保留原有文本。
     * TODO：将本私有方法移至公共业务方法之后，集中组织辅助实现。
     */
    private String bodyPreview(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        int end = body.offsetByCodePoints(0, Math.min(100, body.codePointCount(0, body.length())));
        return body.substring(0, end);
    }

    /**
     * 在同一事务中保存瞬间、图片和标签，并返回详情。
     * TODO：补充 request、当前登录用户 ID 和详情返回值的说明，写清重复瞬间 ID 冲突及图片无效等失败条件。
     * TODO：说明本接口承接客户端草稿发布；图片应已直传并登记，本接口负责校验和建立关联。
     * 请求中的 UUIDv7 及时间格式已由 MVC 校验、转换，业务层直接使用 UUID 和 Instant。
     * TODO：写清图文至少一种、图片可用性、标签与地点整理、关联保存、响应组装及失败回滚流程。
     * TODO：将图片校验前置到数据库写入之前，主流程明确区分校验、保存和响应组装阶段。
     */
    @Transactional
    public MomentResponse createMoment(CreateMomentRequest request, UUID userId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID id = request.id();

        List<UUID> fileIds = request.imageFileIds() == null ? List.of()
                : request.imageFileIds();
        validateContent(request.body(), fileIds);
        List<String> tags = normalizeTags(request.tags());
        MomentLocation location = normalizeLocation(request.location());
        LocalDateTime memoryTime = toDatabaseMemoryTime(request.memoryTime());
        LocalDateTime savedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);

        Moment moment = new Moment();
        moment.setId(id);
        moment.setAuthorUserId(userId);
        moment.setCreatedAt(savedAt);
        applyContent(moment, request.title(), request.body(), request.mood(), memoryTime, location, savedAt);

        try {
            // 主键同时覆盖并发提交和已软删除记录的 ID。
            momentMapper.insert(moment);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "MOMENT_ID_CONFLICT", "瞬间 ID 已被使用");
        }

        List<MomentResponse.Image> images = saveImages(id, userId, fileIds, savedAt);
        List<String> savedTags = saveTags(id, tags, savedAt);
        log.info("已写入瞬间，momentId={}，userId={}，imageCount={}，tagCount={}",
                id, userId, images.size(), savedTags.size());

        return toResponse(moment, images, savedTags, location);
    }

    /**
     * 在同一事务内替换本人瞬间的内容及关联，保留原瞬间和历史关联的标识。
     * TODO：补充本方法业务注释：说明瞬间 ID、全量更新请求、当前用户和详情返回值；交代记录锁定、作者校验、字段校验与整理、内容替换、图片和标签关联替换的执行步骤。
     * TODO：写清 null 与空数组的清空语义、图片移除仅解除关联而保留文件、失败时整体回滚，以及本接口承接详情编辑后的保存请求。
     */
    @Transactional
    public MomentResponse updateMoment(UUID id, UpdateMomentRequest request, UUID userId) {
        Moment moment = requireOwnedMoment(momentMapper.selectForUpdate(id), userId);
        List<UUID> fileIds = request.imageFileIds();
        validateContent(request.body(), fileIds);
        List<String> tags = normalizeTags(request.tags());
        MomentLocation location = normalizeLocation(request.location());
        LocalDateTime memoryTime = toDatabaseMemoryTime(request.memoryTime());
        LocalDateTime savedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

        applyContent(moment, request.title(), request.body(), request.mood(), memoryTime, location, savedAt);
        momentMapper.updateContent(moment);

        // TODO：将图片、标签分别组织为含义明确的“替换关联”步骤，统一抽象层次，避免主方法直接展开标签逐项入库。
        // TODO：明确是否需要保留历史关联 ID 和创建时间，再选择简单的恢复策略；先解除全部关联、再恢复保留项的过程需说明清楚。
        // 先解除旧关联，再按提交顺序恢复或新增，移除的图片文件继续保留。
        momentImageMapper.removeAssociations(id);
        List<MomentResponse.Image> images = saveImages(id, userId, fileIds, savedAt);
        momentTagMapper.removeAssociations(id);
        for (String name : tags) {
            momentTagMapper.saveAssociation(newTag(id, name, savedAt));
        }
        // TODO：补充修改业务 INFO 日志，记录 momentId、userId 及必要的图片数、标签数，遵循项目现有日志风格。
        return toResponse(moment, images, momentTagMapper.selectNames(id), location);
    }

    /**
     * 查询有效的本人瞬间，并签发当前可用的图片读取地址。
     * TODO：补充本方法业务注释：说明用于阅读和编辑回填，入参为瞬间 ID 与当前用户 ID；写清归属检查、图片顺序、标签读取、地点与 UTC 时间转换、详情返回值、只读事务及不存在或非本人时的异常。
     * TODO：整理为查询并确认归属、加载有效图片、加载标签、组装详情的清晰步骤，抽离图片响应和地点转换细节。
     * TODO：读取时与列表统一跳过失效图片，保留文字和其他有效图片；发布、修改时仍严格校验提交的图片。
     */
    @Transactional(readOnly = true)
    public MomentResponse getMoment(UUID id, UUID userId) {
        Moment moment = requireOwnedMoment(momentMapper.selectById(id), userId);
        List<MomentResponse.Image> images = momentImageMapper.selectInDisplayOrder(id)
                .stream().map(image -> new MomentResponse.Image(image.getFileId(),
                        mediaService.createOwnedImageReadUrl(image.getFileId(), userId))).toList();
        List<String> tags = momentTagMapper.selectNames(id);
        MomentLocation location = moment.getLocationName() == null && moment.getLocationAddress() == null
                && moment.getLatitude() == null && moment.getLongitude() == null ? null
                : new MomentLocation(moment.getLatitude(), moment.getLongitude(),
                        moment.getLocationName(), moment.getLocationAddress());
        return toResponse(moment, images, tags, location);
    }

    /**
     * 软删除本人瞬间并解除关联，已删除的本人瞬间可重复提交。
     * TODO：补充本方法业务注释：说明瞬间 ID 和当前用户 ID；交代读取并锁定删除状态、作者校验、已删除时直接返回、软删除与解除图片和标签关联的步骤。
     * TODO：写清无返回数据、保留原图片文件、事务内整体提交或回滚，以及记录不存在或非本人时的业务异常。
     */
    @Transactional
    public void deleteMoment(UUID id, UUID userId) {
        // TODO：结合一期需求简化删除查询和重复删除处理，并与修改流程统一评估锁；明确并发行为后调整，避免只删除一处锁导致关联交叉替换。
        Moment moment = requireOwnedMoment(momentMapper.selectDeletionState(id), userId);
        if (moment.getDeletedAt() != null) {
            return;
        }
        // TODO：删除也属于修改，软删除时同步更新 updated_at；与关联解除统一 UTC 时间及审计字段处理方式。
        momentMapper.deleteById(id);
        momentImageMapper.removeAssociations(id);
        momentTagMapper.removeAssociations(id);
        // TODO：补充首次删除的 INFO 日志，记录 momentId、userId；重复删除可省略或使用 DEBUG。
        // TODO 时光模块实现后，在同一事务中解除此瞬间的时光关联。
    }

    /**
     * 校验瞬间仍然有效且归当前用户所有。
     * TODO：修正注释与实现的差异：当前只检查记录非空和作者归属，未检查删除状态；明确调用方的查询过滤约定。
     * TODO：说明参数是已查询的实体、成功返回原对象，以及不存在或非本人时的业务异常。
     */
    private Moment requireOwnedMoment(Moment moment, UUID userId) {
        if (moment == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "MOMENT_NOT_FOUND", "瞬间不存在");
        }
        if (!userId.equals(moment.getAuthorUserId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅作者可以操作此瞬间");
        }
        return moment;
    }

    /**
     * 应用发布和全量编辑共用的内容字段。
     * TODO：补充各参数的含义与校验前提、地点为 null 时清空地点字段、空白标题转为 null、保存时间用于更新时间；明确无返回值且仅修改传入实体。
     * TODO：改为能表达“填写实体可编辑字段”的明确名称，整理过长参数列表；结合创建、修改流程选择简单复用方式，允许少量直观重复。
     */
    private void applyContent(Moment moment, String title, String body, MomentMood mood, LocalDateTime memoryTime,
                              MomentLocation location, LocalDateTime savedAt) {
        moment.setTitle(StringUtils.hasText(title) ? title : null);
        moment.setBody(body);
        moment.setMood(mood);
        moment.setMemoryTime(memoryTime);
        moment.setUpdatedAt(savedAt);
        moment.setLatitude(location == null ? null : location.latitude());
        moment.setLongitude(location == null ? null : location.longitude());
        moment.setLocationName(location == null ? null : location.name());
        moment.setLocationAddress(location == null ? null : location.address());
    }

    /**
     * TODO：说明各输入数据的含义及返回的详情结构，统一从实体组装地点，移除额外传入的地点参数。
     * TODO：地点转换放入本方法或独立辅助方法，避免调用方重复判断字段是否全部为空。
     */
    private MomentResponse toResponse(Moment moment, List<MomentResponse.Image> images,
                                      List<String> tags, MomentLocation location) {
        return new MomentResponse(moment.getId(), moment.getTitle(), moment.getBody(),
                images, tags, moment.getMood(), moment.getMemoryTime().toInstant(ZoneOffset.UTC), location,
                moment.getCreatedAt().toInstant(ZoneOffset.UTC), moment.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    /**
     * 检查正文与图片是否满足发布要求。
     * TODO：补充入参前提与异常说明：正文可空，fileIds 应为已解析且非 null 的列表；检查图文至少一种、正文 UTF-8 字节上限和图片重复，失败抛参数业务异常；无返回值，不修改入参。
     */
    private void validateContent(String body, List<UUID> fileIds) {
        if (!StringUtils.hasText(body) && fileIds.isEmpty()) {
            throw invalidArgument("请填写正文或添加图片");
        }
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw invalidArgument("正文超出可保存长度");
        }
        if (new HashSet<>(fileIds).size() != fileIds.size()) {
            throw invalidArgument("同一图片不能重复添加");
        }
    }

    /**
     * 清理标签空白、校验长度并去除重复项。
     * TODO：补充参数和返回值约定：列表可为 null，元素应已通过非 null 校验；说明去首尾空白、过滤空白项、保留顺序、按码点校验长度及返回新列表的行为。
     * TODO：统一标签等价和去重规则；Java distinct 与数据库不区分大小写、重音的排序规则存在差异，需明确保留的写法及创建、修改的一致行为。
     */
    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> normalized = tags.stream().map(String::strip)
                .filter(StringUtils::hasText).distinct().toList();
        for (String tag : normalized) {
            if (tag.codePointCount(0, tag.length()) > MAX_TAG_LENGTH) {
                // TODO：错误提示中的标签长度取自 MAX_TAG_LENGTH，保持提示与校验规则一致。
                throw invalidArgument("标签不能超过 64 字符");
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
            throw invalidArgument("发生时间超出可保存范围");
        }
        if (memoryTime.getYear() < 1000 || memoryTime.getYear() > 9999) {
            throw invalidArgument("发生时间超出可保存范围");
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
            throw invalidArgument("经度和纬度必须同时填写");
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
     * 验证图片归属，按提交顺序保存关联并生成访问地址。
     * TODO：拆分图片校验、关联保存、读取地址签名和响应组装职责，方法名称与实际职责保持一致。
     * TODO：说明 fileIds 的校验前提、关联展示顺序、数据库写入行为，以及返回图片响应的顺序。
     */
    private List<MomentResponse.Image> saveImages(UUID momentId, UUID userId, List<UUID> fileIds,
                                                   LocalDateTime savedAt) {
        List<MomentResponse.Image> images = new ArrayList<>(fileIds.size());
        for (UUID fileId : fileIds) {
            String url = mediaService.createOwnedImageReadUrl(fileId, userId);
            MomentImage image = new MomentImage();
            image.setId(UuidCreator.getTimeOrderedEpoch());
            image.setMomentId(momentId);
            image.setFileId(fileId);
            image.setSortOrder(images.size() + 1);
            image.setCreatedAt(savedAt);
            image.setUpdatedAt(savedAt);
            momentImageMapper.saveAssociation(image);
            images.add(new MomentResponse.Image(fileId, url));
        }
        return images;
    }

    /**
     * 保存标签，按数据库排序规则保留首次出现的名称。
     * TODO：补充参数、写入行为和返回值注释：接收已整理的标签名称和保存时间，逐项写入关联，返回实际保存的名称列表，明确排序与等价标签处理约定。
     * TODO：结合标签去重规则统一创建、修改的保存方式；需要保留数据库去重时，解释唯一约束冲突与保留首次写法的原因。
     */
    private List<String> saveTags(UUID momentId, List<String> tags, LocalDateTime savedAt) {
        List<String> savedTags = new ArrayList<>(tags.size());
        for (String name : tags) {
            try {
                momentTagMapper.insert(newTag(momentId, name, savedAt));
                savedTags.add(name);
            } catch (DuplicateKeyException exception) {
                // 标签唯一索引按数据库排序规则比较，保留首次出现的写法。
                log.debug("忽略重复标签，momentId={}", momentId);
            }
        }
        return savedTags;
    }

    /**
     * 创建待保存的瞬间标签关联。
     * TODO：补充 momentId、已整理的标签名称、UTC 保存时间和返回实体的说明；明确生成 UUIDv7 并填写审计字段，本方法只构造对象，实际入库由调用方完成。
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

    /**
     * 创建参数校验失败的业务异常。
     * TODO：补充 message 为面向调用方的错误说明、返回固定状态码和错误码的异常对象；本方法只构造异常，由调用方抛出。
     * TODO：将通用参数错误工厂方法移入 BusinessException，统一状态码和错误码，移除此处的重复包装。
     */
    private BusinessException invalidArgument(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }
}
