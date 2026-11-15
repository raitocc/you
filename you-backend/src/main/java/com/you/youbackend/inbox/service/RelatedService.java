package com.you.youbackend.inbox.service;

import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.response.AlbumListItem;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.circle.entity.Circle;
import com.you.youbackend.circle.entity.CircleShare;
import com.you.youbackend.circle.entity.CircleMember;
import com.you.youbackend.circle.mapper.CircleMemberMapper;
import com.you.youbackend.circle.mapper.CircleShareMapper;
import com.you.youbackend.gathering.mapper.GatheringMemberMapper;
import com.you.youbackend.circle.mapper.CircleMapper;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.gathering.entity.Gathering;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import com.you.youbackend.inbox.entity.UserInbox;
import com.you.youbackend.inbox.mapper.UserInboxMapper;
import com.you.youbackend.inbox.model.*;
import com.you.youbackend.inbox.response.*;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.response.MomentSummaryResponse;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.response.PublicUserResponse;
import com.you.youbackend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Related 读取编排：有效性筛选、游标分页、批量加载与卡片组装。 */
@Log4j2
@Service
@RequiredArgsConstructor
public class RelatedService {
    private static final int DESCRIPTION_PREVIEW_LENGTH = 100;
    /** 沿用圈子分享列表的最小扫描批量，减少失效记录造成的小批往返。 */
    private static final int MIN_CANDIDATE_BATCH_SIZE = 32;
    private final UserInboxMapper inboxMapper;
    private final MomentMapper momentMapper;
    private final AlbumMapper albumMapper;
    private final GatheringMapper gatheringMapper;
    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final CircleShareMapper circleShareMapper;
    private final GatheringMemberMapper gatheringMemberMapper;
    private final MomentService momentService;
    private final AlbumService albumService;
    private final UserService userService;

    /**
     * 浏览当前用户收到且当前可访问的动态，按分享时间、动态 ID 倒序。
     * @param userId 当前认证用户，列表仅查询其信箱
     * @param request 已校验的 GET 泛型分页请求
     * @return 摘要卡片、下一页游标与后续页标记；跨来源分享分别保留
     */
    @Transactional(readOnly = true)
    public ResultPage<RelatedResponse, RelatedCursor> browseRelated(UUID userId, PageRequest<RelatedCursor> request) {
        // 1. 分批读取候选并筛选，补足页大小加一条有效动态。
        List<RelatedContent> rows = loadPage(userId, request);
        boolean hasMore = rows.size() > request.limit();
        List<RelatedContent> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        // 2. 仅补齐实际返回页；同一内容重复出现时共用本次加载的摘要。
        List<RelatedResponse> items = loadResponses(page);
        RelatedCursor nextCursor = null;
        if (hasMore) {
            UserInbox last = page.get(page.size() - 1).inbox();
            nextCursor = new RelatedCursor(last.getSharedAt().toInstant(ZoneOffset.UTC), last.getId());
        }
        log.debug("浏览与你有关动态，userId={}，itemCount={}，hasMore={}", userId, items.size(), hasMore);
        return new ResultPage<>(items, nextCursor, hasMore);
    }

    /** 沿用圈子分享列表的补页流程，内部扫描游标与对外返回游标分别维护。 */
    private List<RelatedContent> loadPage(UUID userId, PageRequest<RelatedCursor> request) {
        RelatedCursor cursor = request.cursor();
        LocalDateTime time = cursor == null ? null : cursorTime(cursor.sharedAt());
        UUID id = cursor == null ? null : cursor.id();
        int targetSize = request.limit() + 1;
        int batchSize = Math.max(targetSize, MIN_CANDIDATE_BATCH_SIZE);
        List<RelatedContent> rows = new ArrayList<>();
        while (rows.size() < targetSize) {
            // 1. 查询有序候选，批量筛选后按原顺序补入有效动态。
            List<UserInbox> candidates = inboxMapper.selectPageCandidates(userId, time, id, batchSize);
            if (candidates.isEmpty()) break;
            loadValidContents(candidates, userId).stream().limit(targetSize - rows.size()).forEach(rows::add);
            // 2. 候选已读尽则结束；否则用原始批次末条继续扫描，包括被过滤的记录。
            if (candidates.size() < batchSize) break;
            UserInbox last = candidates.get(candidates.size() - 1);
            time = last.getSharedAt();
            id = last.getId();
        }
        return rows;
    }

    /** 分类批量筛选候选，并恢复信箱原有的混排顺序。 */
    private List<RelatedContent> loadValidContents(List<UserInbox> candidates, UUID userId) {
        // 1. 同一批内容主记录各读取一次，筛选结果携带实体供后续组装使用。
        Map<UUID, Moment> moments = loadMoments(candidates).stream()
                .collect(Collectors.toMap(Moment::getId, Function.identity()));
        Map<UUID, Album> albums = loadAlbums(candidates).stream()
                .collect(Collectors.toMap(Album::getId, Function.identity()));
        List<RelatedContent> contents = new ArrayList<>(loadGatheringContents(candidates, moments, userId));
        contents.addAll(loadCircleContents(candidates, moments, albums, userId));
        // 2. 分类筛选完成后恢复分享时间顺序，跨来源独立卡片仍保留各自动态 ID。
        Map<UUID, RelatedContent> valid = contents.stream()
                .collect(Collectors.toMap(content -> content.inbox().getId(), Function.identity()));
        return candidates.stream().map(row -> valid.get(row.getId())).filter(Objects::nonNull).toList();
    }

    /** 同行动态：筛选当前成员、有效来源及仍关联该同行的本人原瞬间。 */
    private List<RelatedContent> loadGatheringContents(List<UserInbox> candidates, Map<UUID, Moment> moments, UUID userId) {
        // 1. 批量取得当前用户已加入且有效的同行。
        List<UUID> ids = sourceIds(candidates, InboxSourceType.gathering);
        if (ids.isEmpty()) return List.of();
        Set<UUID> joined = gatheringMemberMapper.selectActiveGatheringIds(ids, userId);
        if (joined.isEmpty()) return List.of();
        Map<UUID, Gathering> gatherings = gatheringMapper.selectByIds(joined).stream()
                .collect(Collectors.toMap(Gathering::getId, Function.identity()));
        // 2. 沿候选顺序检查原瞬间及当前关联，保留已加载来源与瞬间。
        List<RelatedContent> contents = new ArrayList<>();
        for (UserInbox row : candidates) {
            if (row.getSourceType() != InboxSourceType.gathering || row.getObjectType() != InboxObjectType.moment) continue;
            Gathering gathering = gatherings.get(row.getSourceId());
            Moment moment = moments.get(row.getObjectId());
            if (gathering == null || moment == null) continue;
            if (!gathering.getId().equals(moment.getGatheringId()) || !row.getActorUserId().equals(moment.getAuthorUserId())) continue;
            var source = new RelatedSourceResponse(InboxSourceType.gathering, gathering.getId(), gathering.getName());
            contents.add(new RelatedContent(row, source, moment, null));
        }
        return contents;
    }

    /** 圈子动态：分步筛选来源、当前分享、分享者资格及原内容归属。 */
    private List<RelatedContent> loadCircleContents(List<UserInbox> candidates, Map<UUID, Moment> moments,
                                                  Map<UUID, Album> albums, UUID userId) {
        // 1. 读取当前用户已加入的有效圈子，以及候选分享关系。
        List<UUID> ids = sourceIds(candidates, InboxSourceType.circle);
        if (ids.isEmpty()) return List.of();
        List<UUID> joined = circleMemberMapper.selectActiveCircleIds(userId, ids);
        if (joined.isEmpty()) return List.of();
        Map<UUID, Circle> circles = circleMapper.selectByIds(joined).stream()
                .collect(Collectors.toMap(Circle::getId, Function.identity()));
        List<UserInbox> circleRows = candidates.stream().filter(row -> row.getSourceType() == InboxSourceType.circle
                && circles.containsKey(row.getSourceId())).toList();
        if (circleRows.isEmpty()) return List.of();
        Map<UUID, CircleShare> shares = loadShares(circleRows);
        Map<UUID, Set<UUID>> members = loadSharerMemberships(circleRows);
        // 2. 每个阶段只使用本批已加载的数据，匹配后携带来源及原内容。
        List<RelatedContent> contents = new ArrayList<>();
        for (UserInbox row : circleRows) {
            CircleShare share = shares.get(row.getShareId());
            if (!matchesShare(row, share)) continue;
            if (!members.getOrDefault(row.getSourceId(), Set.of()).contains(row.getActorUserId())) continue;
            Moment moment = row.getObjectType() == InboxObjectType.moment ? moments.get(row.getObjectId()) : null;
            Album album = row.getObjectType() == InboxObjectType.album ? albums.get(row.getObjectId()) : null;
            if (!matchesOwner(row, moment, album)) continue;
            Circle circle = circles.get(row.getSourceId());
            var source = new RelatedSourceResponse(InboxSourceType.circle, circle.getId(), circle.getName());
            contents.add(new RelatedContent(row, source, moment, album));
        }
        return contents;
    }

    /** 一次读取候选圈子动态对应的有效分享，撤回记录在 Mapper 中过滤。 */
    private Map<UUID, CircleShare> loadShares(List<UserInbox> rows) {
        // 圈子动态保存分享关系 ID，批量去重后读取。
        List<UUID> ids = rows.stream().map(UserInbox::getShareId).filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : circleShareMapper.selectActiveShares(ids).stream()
                .collect(Collectors.toMap(CircleShare::getId, Function.identity()));
    }

    /** 批量加载候选分享者在对应圈子中的成员资格，按圈子分组。 */
    private Map<UUID, Set<UUID>> loadSharerMemberships(List<UserInbox> rows) {
        // 仅查询本批圈子和分享者，避免逐张卡片检查成员。
        List<UUID> circles = rows.stream().map(UserInbox::getSourceId).distinct().toList();
        List<UUID> actors = rows.stream().map(UserInbox::getActorUserId).distinct().toList();
        return circleMemberMapper.selectActiveMemberships(circles, actors).stream()
                .collect(Collectors.groupingBy(CircleMember::getCircleId,
                        Collectors.mapping(CircleMember::getUserId, Collectors.toSet())));
    }

    /** 分享关系须仍指向信箱记录的来源、分享者和内容。 */
    private boolean matchesShare(UserInbox row, CircleShare share) {
        // 分享是否撤回已在读取时筛选，此处核对引用关系。
        return share != null && row.getSourceId().equals(share.getCircleId())
                && row.getActorUserId().equals(share.getSharerUserId())
                && row.getObjectId().equals(share.getObjectId())
                && row.getObjectType().name().equals(share.getObjectType().name());
    }

    /** 原内容有效且仍属于本次分享者，按内容类型分别判断。 */
    private boolean matchesOwner(UserInbox row, Moment moment, Album album) {
        // 主记录的软删除已在批量查询中过滤。
        return switch (row.getObjectType()) {
            case moment -> moment != null && row.getActorUserId().equals(moment.getAuthorUserId());
            case album -> album != null && row.getActorUserId().equals(album.getOwnerUserId());
        };
    }

    /** 使用筛选阶段保留的内容和来源，批量补齐人物、图片及数量。 */
    private List<RelatedResponse> loadResponses(List<RelatedContent> page) {
        // 1. 从已加载实体中去重取得本页内容，保持零重复主表查询。
        if (page.isEmpty()) return List.of();
        List<Moment> moments = page.stream().map(RelatedContent::moment).filter(Objects::nonNull)
                .collect(Collectors.toMap(Moment::getId, Function.identity(), (first, duplicate) -> first))
                .values().stream().toList();
        List<Album> albums = page.stream().map(RelatedContent::album).filter(Objects::nonNull)
                .collect(Collectors.toMap(Album::getId, Function.identity(), (first, duplicate) -> first))
                .values().stream().toList();
        // 2. 作者、所有者与分享者统一加载，缺失资料沿用 null。
        Set<UUID> profileIds = page.stream().map(content -> content.inbox().getActorUserId()).collect(Collectors.toSet());
        moments.forEach(moment -> profileIds.add(moment.getAuthorUserId()));
        albums.forEach(album -> profileIds.add(album.getOwnerUserId()));
        Map<UUID, PublicUserResponse> profiles = userService.getPublicUsers(profileIds);
        Map<UUID, MomentSummaryResponse> momentCards = momentService.loadAuthorizedSummaries(moments, profiles).stream()
                .collect(Collectors.toMap(MomentSummaryResponse::id, Function.identity()));
        Map<UUID, RelatedAlbumSummaryResponse> albumCards = loadAlbumSummaries(albums, profiles);
        // 3. 转换阶段只映射本页已加载的数据，卡片顺序沿用原动态。
        return page.stream().map(content -> toResponse(content.inbox(), content.source(),
                profiles.get(content.inbox().getActorUserId()), momentCards.get(content.inbox().getObjectId()),
                albumCards.get(content.inbox().getObjectId()))).toList();
    }

    /** 批量读取候选引用的有效瞬间，主表结果在筛选及摘要组装间共用。 */
    private List<Moment> loadMoments(List<UserInbox> rows) {
        // 同一内容在一批内按 ID 去重。
        List<UUID> ids = rows.stream().filter(row -> row.getObjectType() == InboxObjectType.moment)
                .map(UserInbox::getObjectId).distinct().toList();
        return ids.isEmpty() ? List.of() : momentMapper.selectByIds(ids);
    }

    /** 批量读取候选引用的有效时光，主表结果在筛选及摘要组装间共用。 */
    private List<Album> loadAlbums(List<UserInbox> rows) {
        // 同一内容在一批内按 ID 去重。
        List<UUID> ids = rows.stream().filter(row -> row.getObjectType() == InboxObjectType.album)
                .map(UserInbox::getObjectId).distinct().toList();
        return ids.isEmpty() ? List.of() : albumMapper.selectByIds(ids);
    }

    /** 提取指定来源类型的去重空间 ID。 */
    private List<UUID> sourceIds(List<UserInbox> rows, InboxSourceType type) {
        // 分类查询保持同行和圈子的标识范围清楚。
        return rows.stream().filter(row -> row.getSourceType() == type).map(UserInbox::getSourceId).distinct().toList();
    }

    /** 复用时光批量数量、封面能力，补上介绍摘要和已加载所有者资料。 */
    private Map<UUID, RelatedAlbumSummaryResponse> loadAlbumSummaries(List<Album> albums, Map<UUID, PublicUserResponse> profiles) {
        // 1. 批量加载封面和数量，映射只使用本页数据。
        Map<UUID, AlbumListItem> items = albumService.loadAuthorizedItems(albums).stream()
                .collect(Collectors.toMap(AlbumListItem::id, Function.identity()));
        // 2. 介绍按 Unicode 码点截取，避免拆开表情字符。
        return albums.stream().collect(Collectors.toMap(Album::getId, album -> {
            AlbumListItem item = items.get(album.getId());
            return new RelatedAlbumSummaryResponse(album.getId(), album.getName(), preview(album.getDescription()),
                    item.coverUrl(), item.momentCount(), profiles.get(album.getOwnerUserId()));
        }));
    }

    /** 将动态与已加载摘要转换为响应，按类型保留对应内容。 */
    private RelatedResponse toResponse(UserInbox row, RelatedSourceResponse source, PublicUserResponse actor,
                                       MomentSummaryResponse moment, RelatedAlbumSummaryResponse album) {
        // 内容类型明确约束对应摘要，跨类型相同 UUID 也分别解释。
        return new RelatedResponse(row.getId(), row.getSharedAt().toInstant(ZoneOffset.UTC), row.getObjectType(),
                source, row.getShareId(), actor, row.getObjectType() == InboxObjectType.moment ? moment : null,
                row.getObjectType() == InboxObjectType.album ? album : null);
    }

    /** 截取介绍开头最多 100 个 Unicode 码点，保留空值与空字符串原样。 */
    private String preview(String description) {
        // offsetByCodePoints 按完整码点定位边界。
        if (description == null) return null;
        int count = Math.min(description.codePointCount(0, description.length()), DESCRIPTION_PREVIEW_LENGTH);
        return description.substring(0, description.offsetByCodePoints(0, count));
    }

    /** 将时间转换为 MySQL DATETIME 可表示的 UTC 毫秒值。 */
    private LocalDateTime cursorTime(Instant value) {
        // 与现有分页保持相同的年份范围及精度校验。
        try {
            LocalDateTime time = LocalDateTime.ofInstant(value, ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
            if (time.getYear() >= 1000 && time.getYear() <= 9999) return time;
        } catch (DateTimeException exception) {
            throw BusinessException.invalidArgument("游标时间超出可查询范围");
        }
        throw BusinessException.invalidArgument("游标时间超出可查询范围");
    }
}
