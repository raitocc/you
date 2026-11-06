package com.you.youbackend.moment.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.entity.MomentImage;
import com.you.youbackend.moment.entity.MomentTag;
import com.you.youbackend.moment.mapper.MomentImageMapper;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.mapper.MomentTagMapper;
import com.you.youbackend.moment.model.MomentFeedCursor;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Log4j2
@Service
@RequiredArgsConstructor
public class MomentService {

    private static final Duration MAX_FUTURE_ID_SKEW = Duration.ofMinutes(5);
    private static final int MAX_BODY_BYTES = 65_535;
    private static final int MAX_TAG_LENGTH = 64;
    private static final int COORDINATE_SCALE = 7;

    private final MomentMapper momentMapper;
    private final MomentImageMapper momentImageMapper;
    private final MomentTagMapper momentTagMapper;
    private final MediaService mediaService;

    /**
     * 分页读取本人瞬间，并为每张卡片补充有效图片数和封面。
     */
    @Transactional(readOnly = true)
    public ResultPage<MomentFeedItem> browseYours(UUID userId, String cursor, int limit) {
        MomentFeedCursor boundary = cursor == null ? null : MomentFeedCursor.decode(cursor);
        // 多查一条判断是否还有下一页，图片只按实际返回的记录批量查询。
        List<Moment> rows = momentMapper.selectFeed(userId, boundary, limit + 1);
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

        List<MomentFeedItem> items = page.stream().map(moment -> {
            List<MomentImage> validImages = imagesByMoment.getOrDefault(moment.getId(), List.of());
            String coverUrl = validImages.isEmpty() ? null
                    : mediaService.createReadUrl(imageKeys.get(validImages.get(0).getFileId()));
            return new MomentFeedItem(moment.getId(), moment.getTitle(), bodyPreview(moment.getBody()),
                    coverUrl, validImages.size(), moment.getMood(), moment.getMemoryTime().toInstant(ZoneOffset.UTC));
        }).toList();
        // 下一页从本页最后一条之后开始，多查的那条留到下一页。
        Moment last = page.get(page.size() - 1);
        String nextCursor = hasMore ? new MomentFeedCursor(last.getMemoryTime(), last.getId()).encode() : null;
        return new ResultPage<>(items, nextCursor, hasMore);
    }

    /**
     * 保留正文开头最多 100 个 Unicode 字符。
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
     */
    @Transactional
    public MomentResponse createMoment(CreateMomentRequest request, UUID userId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID id = UUID.fromString(request.id());
        // UUIDv7 的高 48 位记录草稿 ID 的生成时间。
        long idTimestamp = id.getMostSignificantBits() >>> 16;
        if (idTimestamp > now.plus(MAX_FUTURE_ID_SKEW).toEpochMilli()) {
            throw invalidArgument("瞬间 ID 的时间不能超前服务端超过 5 分钟");
        }

        List<UUID> fileIds = request.imageFileIds() == null ? List.of()
                : request.imageFileIds().stream().map(UUID::fromString).toList();
        validateContent(request.body(), fileIds);
        List<String> tags = normalizeTags(request.tags());
        MomentResponse.Location location = normalizeLocation(request.location());
        LocalDateTime memoryTime = parseMemoryTime(request.memoryTime());
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
     */
    @Transactional
    public MomentResponse updateMoment(UUID id, UpdateMomentRequest request, UUID userId) {
        Moment moment = requireOwnedMoment(momentMapper.selectForUpdate(id), userId);
        List<UUID> fileIds = request.imageFileIds().stream().map(UUID::fromString).toList();
        validateContent(request.body(), fileIds);
        List<String> tags = normalizeTags(request.tags());
        MomentResponse.Location location = normalizeLocation(request.location());
        LocalDateTime memoryTime = parseMemoryTime(request.memoryTime());
        LocalDateTime savedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

        applyContent(moment, request.title(), request.body(), request.mood(), memoryTime, location, savedAt);
        momentMapper.updateContent(moment);

        // 先解除旧关联，再按提交顺序恢复或新增，移除的图片文件继续保留。
        momentImageMapper.removeAssociations(id);
        List<MomentResponse.Image> images = saveImages(id, userId, fileIds, savedAt);
        momentTagMapper.removeAssociations(id);
        for (String name : tags) {
            momentTagMapper.saveAssociation(newTag(id, name, savedAt));
        }
        return toResponse(moment, images, momentTagMapper.selectNames(id), location);
    }

    /**
     * 查询有效的本人瞬间，并签发当前可用的图片读取地址。
     */
    @Transactional(readOnly = true)
    public MomentResponse getMoment(UUID id, UUID userId) {
        Moment moment = requireOwnedMoment(momentMapper.selectById(id), userId);
        List<MomentResponse.Image> images = momentImageMapper.selectInDisplayOrder(id)
                .stream().map(image -> new MomentResponse.Image(image.getFileId(),
                        mediaService.createOwnedImageReadUrl(image.getFileId(), userId))).toList();
        List<String> tags = momentTagMapper.selectNames(id);
        MomentResponse.Location location = moment.getLocationName() == null && moment.getLocationAddress() == null
                && moment.getLatitude() == null && moment.getLongitude() == null ? null
                : new MomentResponse.Location(moment.getLatitude(), moment.getLongitude(),
                        moment.getLocationName(), moment.getLocationAddress());
        return toResponse(moment, images, tags, location);
    }

    /**
     * 软删除本人瞬间并解除关联，已删除的本人瞬间可重复提交。
     */
    @Transactional
    public void deleteMoment(UUID id, UUID userId) {
        Moment moment = requireOwnedMoment(momentMapper.selectDeletionState(id), userId);
        if (moment.getDeletedAt() != null) {
            return;
        }
        momentMapper.deleteById(id);
        momentImageMapper.removeAssociations(id);
        momentTagMapper.removeAssociations(id);
        // TODO 时光模块实现后，在同一事务中解除此瞬间的时光关联。
    }

    /**
     * 校验瞬间仍然有效且归当前用户所有。
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
     */
    private void applyContent(Moment moment, String title, String body, MomentMood mood, LocalDateTime memoryTime,
                              MomentResponse.Location location, LocalDateTime savedAt) {
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

    private MomentResponse toResponse(Moment moment, List<MomentResponse.Image> images,
                                      List<String> tags, MomentResponse.Location location) {
        return new MomentResponse(moment.getId(), moment.getTitle(), moment.getBody(),
                images, tags, moment.getMood(), moment.getMemoryTime().toInstant(ZoneOffset.UTC), location,
                moment.getCreatedAt().toInstant(ZoneOffset.UTC), moment.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    /**
     * 检查正文与图片是否满足发布要求。
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
     */
    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> normalized = tags.stream().map(String::strip)
                .filter(StringUtils::hasText).distinct().toList();
        for (String tag : normalized) {
            if (tag.codePointCount(0, tag.length()) > MAX_TAG_LENGTH) {
                throw invalidArgument("标签不能超过 64 字符");
            }
        }
        return normalized;
    }

    /**
     * 解析带时区的发生时间，转换为可入库的 UTC 毫秒时间。
     */
    private LocalDateTime parseMemoryTime(String value) {
        LocalDateTime memoryTime;
        try {
            memoryTime = LocalDateTime.ofInstant(OffsetDateTime.parse(value).toInstant(), ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeException exception) {
            throw invalidArgument("发生时间必须使用带时区偏移的 ISO 8601 格式");
        }
        if (memoryTime.getYear() < 1000 || memoryTime.getYear() > 9999) {
            throw invalidArgument("发生时间超出可保存范围");
        }
        return memoryTime;
    }

    /**
     * 校验经纬度成对填写，并统一坐标精度。
     */
    private MomentResponse.Location normalizeLocation(CreateMomentRequest.Location location) {
        if (location == null) {
            return null;
        }
        if ((location.latitude() == null) != (location.longitude() == null)) {
            throw invalidArgument("经度和纬度必须同时填写");
        }
        if (location.latitude() == null) {
            return new MomentResponse.Location(null, null, location.name(), location.address());
        }
        return new MomentResponse.Location(
                location.latitude().setScale(COORDINATE_SCALE, RoundingMode.HALF_UP),
                location.longitude().setScale(COORDINATE_SCALE, RoundingMode.HALF_UP),
                location.name(), location.address());
    }

    /**
     * 验证图片归属，按提交顺序保存关联并生成访问地址。
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
     */
    private BusinessException invalidArgument(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }
}
