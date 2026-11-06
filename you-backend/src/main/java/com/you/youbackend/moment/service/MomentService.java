package com.you.youbackend.moment.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.PageResult;
import com.you.youbackend.media.entity.MediaFile;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.mapper.MomentRelationMapper;
import com.you.youbackend.moment.mapper.MomentRelationMapper.Target;
import com.you.youbackend.moment.model.MomentLocation;
import com.you.youbackend.moment.request.EditMomentRequest;
import com.you.youbackend.moment.request.PublishMomentRequest;
import com.you.youbackend.moment.response.DeleteMomentResponse;
import com.you.youbackend.moment.response.MomentResponse;
import com.you.youbackend.user.mapper.UserMapper;
import com.you.youbackend.user.model.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.DateTimeException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 瞬间读写、可见范围检查与关联维护。 */
@Service
@RequiredArgsConstructor
public class MomentService {

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final MomentMapper moments;
    private final MomentRelationMapper relations;
    private final UserMapper users;
    private final MediaService media;
    private final MomentCursor cursors;

    /** 发布瞬间，返回资源及是否首次创建。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PublishResult publish(UUID userId, PublishMomentRequest request) {
        UUID id = request.getId();
        if (id == null || id.version() != 7 || id.variant() != 2) {
            throw invalid("瞬间 ID 必须为 UUIDv7");
        }
        Moment existing = moments.findById(id);
        if (existing != null) {
            return replay(userId, existing);
        }

        LocalDateTime now = now();
        Moment moment = new Moment();
        moment.setId(id);
        moment.setAuthorUserId(userId);
        moment.setCreatedAt(now);
        moment.setUpdatedAt(now);
        moment.setMemoryTime(now);
        applyContent(moment, request);

        List<UUID> images = request.contains("imageFileIds") ? ids(request.getImageFileIds(), "imageFileIds") : List.of();
        List<String> tags = request.contains("tags") ? tags(request.getTags()) : List.of();
        List<UUID> circles = request.contains("circleIds") ? ids(request.getCircleIds(), "circleIds") : List.of();
        requireContent(moment, images);

        try {
            moments.insert(moment);
        } catch (DuplicateKeyException exception) {
            return replay(userId, moments.findById(id));
        }
        validateImages(userId, images);
        validateTargets(userId, moment.getGatheringId(), circles);
        saveImages(id, images, now);
        saveTags(id, tags, now);
        updateSharing(moment, null, List.of(), circles);
        return new PublishResult(response(moment, userId, sources(moment, userId), false), true);
    }

    /** 查询当前用户所选 Tab 的下一页信息流。 */
    @Transactional(readOnly = true)
    public PageResult<MomentResponse> browse(UUID userId, String tab, PageRequest page) {
        if (!"yours".equals(tab) && !"related".equals(tab)) {
            throw invalid("tab 必须为 yours 或 related");
        }
        MomentCursor.Position after = page.getCursor() == null ? null : cursors.decode(userId, tab, page.getCursor());
        int limit = page.getLimit();
        List<Moment> rows = moments.selectFeed(userId, "related".equals(tab),
                after == null ? null : after.memoryTime(), after == null ? null : after.id(), limit + 1);
        List<Moment> current = rows.subList(0, Math.min(limit, rows.size()));
        List<MomentResponse> items = current.stream()
                .map(moment -> response(moment, userId, sources(moment, userId), false))
                .toList();
        String next = null;
        if (rows.size() > limit) {
            Moment last = current.get(current.size() - 1);
            next = cursors.encode(userId, tab, new MomentCursor.Position(last.getMemoryTime(), last.getId()));
        }
        return new PageResult<>(items, next);
    }

    /** 读取详情，在签发图片地址前检查访问资格。 */
    @Transactional(readOnly = true)
    public MomentResponse detail(UUID userId, UUID id) {
        Moment moment = moments.selectById(id);
        requireActive(moment);
        List<MomentResponse.Source> sources = sources(moment, userId);
        if (!userId.equals(moment.getAuthorUserId()) && sources.isEmpty()) {
            throw notFound();
        }
        return response(moment, userId, sources, true);
    }

    /** 按版本合并编辑内容并原子替换指定的关联集合。 */
    @Transactional
    public MomentResponse edit(UUID userId, UUID id, String ifMatch, EditMomentRequest request) {
        LocalDateTime version = parseVersion(ifMatch);
        Moment moment = moments.lockById(id);
        requireActive(moment);
        requireAuthor(moment, userId);
        checkVersion(moment, version);
        if (!request.hasChanges()) {
            throw invalid("请至少提供一个可编辑字段");
        }

        UUID oldJourney = moment.getGatheringId();
        List<UUID> oldCircles = relations.lockSharedCircleIds(id);
        List<UUID> circles = request.contains("circleIds") ? ids(request.getCircleIds(), "circleIds") : oldCircles;
        List<UUID> images = request.contains("imageFileIds")
                ? ids(request.getImageFileIds(), "imageFileIds")
                : relations.selectImageIds(id);
        List<String> tags = request.contains("tags") ? tags(request.getTags()) : List.of();

        applyContent(moment, request);
        requireContent(moment, images);
        validateImages(userId, images);
        validateTargets(userId, moment.getGatheringId(), circles);

        LocalDateTime now = nextVersion(moment);
        moment.setUpdatedAt(now);
        moments.updateContent(moment);
        if (request.contains("imageFileIds")) {
            relations.removeImages(id, now);
            saveImages(id, images, now);
        }
        if (request.contains("tags")) {
            relations.removeTags(id, now);
            saveTags(id, tags, now);
        }
        updateSharing(moment, oldJourney, oldCircles, circles);
        return response(moment, userId, sources(moment, userId), false);
    }

    /** 按版本软删除瞬间及其引用，允许作者重复确认删除结果。 */
    @Transactional
    public DeleteMomentResponse delete(UUID userId, UUID id, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw preconditionRequired();
        }
        Moment moment = moments.lockById(id);
        if (moment != null && userId.equals(moment.getAuthorUserId()) && moment.getDeletedAt() != null) {
            return new DeleteMomentResponse(id, true);
        }
        requireActive(moment);
        requireAuthor(moment, userId);
        checkVersion(moment, parseVersion(ifMatch));

        LocalDateTime now = nextVersion(moment);
        moment.setGatheringId(null);
        moment.setUpdatedAt(now);
        moment.setDeletedAt(now);
        moments.updateContent(moment);
        relations.removeImages(id, now);
        relations.removeTags(id, now);
        relations.removeAlbums(id, now);
        for (UUID circleId : relations.lockSharedCircleIds(id)) {
            relations.removeShare(id, circleId, now);
        }
        relations.removeAllInbox(id, now);
        return new DeleteMomentResponse(id, true);
    }

    public record PublishResult(MomentResponse moment, boolean created) {}

    private PublishResult replay(UUID userId, Moment moment) {
        if (moment == null || !userId.equals(moment.getAuthorUserId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RESOURCE_CONFLICT", "瞬间 ID 已被占用");
        }
        if (moment.getDeletedAt() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "MOMENT_DELETED", "该瞬间已删除");
        }
        return new PublishResult(response(moment, userId, sources(moment, userId), false), false);
    }

    private static void applyContent(Moment moment, EditMomentRequest request) {
        if (request.contains("title")) {
            moment.setTitle(request.getTitle());
        }
        if (request.contains("body")) {
            String body = request.getBody();
            if (body != null && body.getBytes(StandardCharsets.UTF_8).length > 60_000) {
                throw invalid("正文最多为 60000 个 UTF-8 字节");
            }
            moment.setBody(body);
        }
        if (request.contains("mood")) {
            moment.setMood(request.getMood());
        }
        if (request.contains("memoryTime")) {
            OffsetDateTime time = request.getMemoryTime();
            if (time == null) {
                throw invalid("发生时间不能为空");
            }
            LocalDateTime utc = LocalDateTime.ofInstant(time.toInstant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
            if (utc.getYear() < 1000 || utc.getYear() > 9999) {
                throw invalid("发生时间超出可保存的范围");
            }
            moment.setMemoryTime(utc);
        }
        if (request.contains("location")) {
            MomentLocation location = request.getLocation();
            if (location != null && (location.latitude() == null) != (location.longitude() == null)) {
                throw invalid("经纬度须同时提供或同时为空");
            }
            moment.setLocationName(location == null ? null : location.name());
            moment.setLocationAddress(location == null ? null : location.address());
            moment.setLatitude(location == null ? null : location.latitude());
            moment.setLongitude(location == null ? null : location.longitude());
        }
        if (request.contains("journeyId")) {
            moment.setGatheringId(request.getJourneyId());
        }
    }

    private static void requireContent(Moment moment, List<UUID> images) {
        if ((moment.getBody() == null || moment.getBody().isBlank()) && images.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_CONTENT", "请填写正文或添加图片");
        }
    }

    private static List<UUID> ids(List<UUID> values, String field) {
        if (values == null || values.stream().anyMatch(Objects::isNull)
                || new HashSet<>(values).size() != values.size()) {
            throw invalid(field + " 必须为无重复且不含空值的数组");
        }
        return values;
    }

    private static List<String> tags(List<String> values) {
        if (values == null) {
            throw invalid("tags 必须为数组");
        }
        List<String> normalized = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String tag = value == null ? "" : value.strip();
            if (tag.isEmpty() || tag.codePointCount(0, tag.length()) > 64 || !unique.add(tag)) {
                throw invalid("标签应为 1～64 个字符，去除首尾空白后不得重复");
            }
            normalized.add(tag);
        }
        return normalized;
    }

    private void validateImages(UUID userId, List<UUID> images) {
        for (UUID fileId : images.stream().sorted().toList()) {
            MediaFile file = relations.lockImage(fileId);
            if (file == null || !userId.equals(file.getOwnerUserId()) || !IMAGE_TYPES.contains(file.getMimeType())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "FILE_NOT_USABLE", "请选择已上传完成的本人图片");
            }
        }
    }

    private void validateTargets(UUID userId, UUID journey, List<UUID> circles) {
        if (journey != null && relations.lockJourney(journey, userId) == null) {
            throw new MomentTargetUnavailableException("journeyId", journey);
        }
        for (UUID circle : circles.stream().sorted().toList()) {
            if (relations.lockCircle(circle, userId) == null) {
                throw new MomentTargetUnavailableException("circleIds", circle);
            }
        }
    }

    private void saveImages(UUID id, List<UUID> images, LocalDateTime now) {
        for (int index = 0; index < images.size(); index++) {
            relations.saveImage(UuidCreator.getTimeOrderedEpoch(), id, images.get(index), index + 1, now);
        }
    }

    private void saveTags(UUID id, List<String> tags, LocalDateTime now) {
        for (String tag : tags) {
            relations.saveTag(UuidCreator.getTimeOrderedEpoch(), id, tag, now);
        }
    }

    private void updateSharing(Moment moment, UUID oldJourney, List<UUID> oldCircles, List<UUID> circles) {
        UUID id = moment.getId();
        UUID newJourney = moment.getGatheringId();
        LocalDateTime now = moment.getUpdatedAt();
        if (!Objects.equals(oldJourney, newJourney)) {
            if (oldJourney != null) {
                relations.removeInbox(id, "gathering", oldJourney, now);
            }
            if (newJourney != null) {
                addInbox(moment, newJourney, true);
            }
        }
        for (UUID oldCircle : oldCircles) {
            if (!circles.contains(oldCircle)) {
                relations.removeShare(id, oldCircle, now);
                relations.removeInbox(id, "circle", oldCircle, now);
            }
        }
        for (UUID circle : circles) {
            if (!oldCircles.contains(circle)) {
                relations.saveShare(UuidCreator.getTimeOrderedEpoch(), circle, id, moment.getAuthorUserId(), now);
                addInbox(moment, circle, false);
            }
        }
    }

    private void addInbox(Moment moment, UUID sourceId, boolean journey) {
        UUID actor = moment.getAuthorUserId();
        for (UUID recipient : relations.selectRecipients(sourceId, actor, journey)) {
            relations.insertInbox(UuidCreator.getTimeOrderedEpoch(), recipient, actor,
                    journey ? "gathering" : "circle", sourceId, moment.getId(), moment.getUpdatedAt());
        }
    }

    private List<MomentResponse.Source> sources(Moment moment, UUID userId) {
        boolean own = userId.equals(moment.getAuthorUserId());
        List<MomentResponse.Source> result = new ArrayList<>();
        if (moment.getGatheringId() != null) {
            Target journey = relations.selectJourney(moment.getGatheringId(), userId, own);
            if (journey != null) {
                result.add(new MomentResponse.Source("journey", journey.id(), journey.name(),
                        "/gathering/journeys/" + journey.id()));
            }
        }
        for (Target circle : relations.selectCircles(moment.getId(), userId, own)) {
            result.add(new MomentResponse.Source("circle", circle.id(), circle.name(),
                    "/circle/circles/" + circle.id()));
        }
        return result;
    }

    private MomentResponse response(Moment moment, UUID userId, List<MomentResponse.Source> sources, boolean detail) {
        UUID id = moment.getId();
        boolean own = userId.equals(moment.getAuthorUserId());
        AppUser author = users.selectById(moment.getAuthorUserId());
        UUID avatarId = author == null ? null : author.getAvatarFileId();
        MomentResponse.Author authorResponse = new MomentResponse.Author(moment.getAuthorUserId(),
                author == null ? null : author.getNickname(), avatarId,
                avatarId == null ? null : media.getReadUrl(avatarId));
        List<MomentResponse.Image> images = relations.selectImages(id).stream()
                .map(image -> new MomentResponse.Image(image.fileId(), image.sortOrder(),
                        media.createReadUrl(image.storageKey()), image.mimeType()))
                .toList();
        List<MomentResponse.Collection> collections = detail
                ? relations.selectAlbums(id, userId).stream()
                        .map(album -> new MomentResponse.Collection(album.id(), album.name(),
                                "/album/collections/" + album.id())).toList()
                : null;
        MomentLocation location = null;
        if (moment.getLocationName() != null || moment.getLocationAddress() != null || moment.getLatitude() != null) {
            location = new MomentLocation(moment.getLocationName(), moment.getLocationAddress(),
                    moment.getLatitude(), moment.getLongitude());
        }
        return new MomentResponse(id, authorResponse, moment.getTitle(), moment.getBody(), moment.getMood(), images,
                relations.selectTags(id), timestamp(moment.getMemoryTime()), location, sources, collections, own, own,
                own, timestamp(moment.getCreatedAt()), timestamp(moment.getUpdatedAt()));
    }

    private void requireAuthor(Moment moment, UUID userId) {
        if (userId.equals(moment.getAuthorUserId())) {
            return;
        }
        if (sources(moment, userId).isEmpty()) {
            throw notFound();
        }
        throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "只有作者可以修改或删除瞬间");
    }

    private static void requireActive(Moment moment) {
        if (moment == null || moment.getDeletedAt() != null) {
            throw notFound();
        }
    }

    private static LocalDateTime parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw preconditionRequired();
        }
        if (ifMatch.length() >= 2 && ifMatch.startsWith("\"") && ifMatch.endsWith("\"")) {
            try {
                String token = ifMatch.substring(1, ifMatch.length() - 1);
                LocalDateTime version = LocalDateTime.ofInstant(java.time.Instant.parse(token), ZoneOffset.UTC);
                if (timestamp(version).equals(token)) {
                    return version;
                }
            } catch (DateTimeException exception) {
                throw invalid("If-Match 必须为双引号包裹的 updatedAt");
            }
        }
        throw invalid("If-Match 必须为双引号包裹的 updatedAt");
    }

    private static void checkVersion(Moment moment, LocalDateTime version) {
        if (!moment.getUpdatedAt().equals(version)) {
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "瞬间已更新，请刷新后重试");
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    private static LocalDateTime nextVersion(Moment moment) {
        LocalDateTime now = now();
        return now.isAfter(moment.getUpdatedAt()) ? now : moment.getUpdatedAt().plus(1, ChronoUnit.MILLIS);
    }

    private static String timestamp(LocalDateTime time) {
        return TIMESTAMP.format(time.toInstant(ZoneOffset.UTC));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message);
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "瞬间不存在或不可访问");
    }

    private static BusinessException preconditionRequired() {
        return new BusinessException(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED", "请提供 If-Match");
    }
}
