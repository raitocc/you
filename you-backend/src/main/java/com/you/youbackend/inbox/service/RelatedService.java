package com.you.youbackend.inbox.service;

import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.response.AlbumListItem;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.circle.entity.Circle;
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
    private final UserInboxMapper inboxMapper;
    private final MomentMapper momentMapper;
    private final AlbumMapper albumMapper;
    private final GatheringMapper gatheringMapper;
    private final CircleMapper circleMapper;
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
        // 1. 数据库按三类来源筛选后合并排序，多读一条判断后续页。
        List<UserInbox> rows = queryPage(userId, request);
        boolean hasMore = rows.size() > request.limit();
        List<UserInbox> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        // 2. 仅补齐实际返回页；同一内容重复出现时共用本次加载的摘要。
        List<RelatedResponse> items = loadResponses(page);
        RelatedCursor nextCursor = null;
        if (hasMore) {
            UserInbox last = page.get(page.size() - 1);
            nextCursor = new RelatedCursor(last.getSharedAt().toInstant(ZoneOffset.UTC), last.getId());
        }
        log.debug("浏览与你有关动态，userId={}，itemCount={}，hasMore={}", userId, items.size(), hasMore);
        return new ResultPage<>(items, nextCursor, hasMore);
    }

    /** 转换 UTC 游标，交给 Mapper 筛选当前有效来源和原内容。 */
    private List<UserInbox> queryPage(UUID userId, PageRequest<RelatedCursor> request) {
        // 首屏省略边界；后续使用上一页末条的分享时间和信箱 ID。
        RelatedCursor cursor = request.cursor();
        LocalDateTime time = cursor == null ? null : cursorTime(cursor.sharedAt());
        UUID id = cursor == null ? null : cursor.id();
        return inboxMapper.selectRelatedPage(userId, time, id, request.limit() + 1);
    }

    /** 批量加载本页内容、人物及来源，数据读取在映射前完成。 */
    private List<RelatedResponse> loadResponses(List<UserInbox> page) {
        // 1. 按类型去重读取内容主记录，保持空列表零查询。
        if (page.isEmpty()) return List.of();
        List<Moment> moments = loadMoments(page);
        List<Album> albums = loadAlbums(page);
        // 2. 作者、所有者与分享者统一加载，缺失资料沿用 null。
        Set<UUID> profileIds = page.stream().map(UserInbox::getActorUserId).collect(Collectors.toSet());
        moments.forEach(moment -> profileIds.add(moment.getAuthorUserId()));
        albums.forEach(album -> profileIds.add(album.getOwnerUserId()));
        Map<UUID, PublicUserResponse> profiles = userService.getPublicUsers(profileIds);
        // 3. 批量加载内容摘要及来源，循环组装阶段只读取这些结果。
        Map<UUID, RelatedSourceResponse> sources = loadSources(page);
        Map<UUID, MomentSummaryResponse> momentCards = momentService.loadAuthorizedSummaries(moments, profiles).stream()
                .collect(Collectors.toMap(MomentSummaryResponse::id, Function.identity()));
        Map<UUID, RelatedAlbumSummaryResponse> albumCards = loadAlbumSummaries(albums, profiles);
        return page.stream().map(row -> toResponse(row, sources.get(row.getId()),
                profiles.get(row.getActorUserId()), momentCards.get(row.getObjectId()), albumCards.get(row.getObjectId()))).toList();
    }

    /** 读取已由分页查询确认可访问的瞬间，按内容 ID 去重。 */
    private List<Moment> loadMoments(List<UserInbox> page) {
        // 同一内容的跨空间分享复用一条实体，原动态顺序由最后组装保留。
        List<UUID> ids = page.stream().filter(row -> row.getObjectType() == InboxObjectType.moment)
                .map(UserInbox::getObjectId).distinct().toList();
        return ids.isEmpty() ? List.of() : momentMapper.selectByIds(ids);
    }

    /** 读取已由分页查询确认可访问的时光，按内容 ID 去重。 */
    private List<Album> loadAlbums(List<UserInbox> page) {
        // 时光内部变化通过当前主记录和后续数量统计反映。
        List<UUID> ids = page.stream().filter(row -> row.getObjectType() == InboxObjectType.album)
                .map(UserInbox::getObjectId).distinct().toList();
        return ids.isEmpty() ? List.of() : albumMapper.selectByIds(ids);
    }

    /** 按来源类型各查一次名称，再建立动态 ID 到来源响应的映射。 */
    private Map<UUID, RelatedSourceResponse> loadSources(List<UserInbox> page) {
        // 1. 分类加载来源，同行和圈子 ID 各自在自己的类型范围内解释。
        List<UUID> gatheringIds = sourceIds(page, InboxSourceType.gathering);
        List<UUID> circleIds = sourceIds(page, InboxSourceType.circle);
        Map<UUID, Gathering> gatherings = gatheringIds.isEmpty() ? Map.of() : gatheringMapper.selectByIds(gatheringIds)
                .stream().collect(Collectors.toMap(Gathering::getId, Function.identity()));
        Map<UUID, Circle> circles = circleIds.isEmpty() ? Map.of() : circleMapper.selectByIds(circleIds)
                .stream().collect(Collectors.toMap(Circle::getId, Function.identity()));
        // 2. 只使用当前只读事务内已筛选且加载的来源完成转换。
        return page.stream().collect(Collectors.toMap(UserInbox::getId, row -> {
            String name = switch (row.getSourceType()) {
                case gathering -> gatherings.get(row.getSourceId()).getName();
                case circle -> circles.get(row.getSourceId()).getName();
            };
            return new RelatedSourceResponse(row.getSourceType(), row.getSourceId(), name);
        }));
    }

    /** 提取指定来源类型的去重空间 ID。 */
    private List<UUID> sourceIds(List<UserInbox> page, InboxSourceType type) {
        // 分类查询避免混用同行与圈子的标识。
        return page.stream().filter(row -> row.getSourceType() == type).map(UserInbox::getSourceId).distinct().toList();
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
