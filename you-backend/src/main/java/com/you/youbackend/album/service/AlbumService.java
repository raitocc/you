package com.you.youbackend.album.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.entity.AlbumMoment;
import com.you.youbackend.album.entity.AlbumMomentHistory;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.mapper.AlbumHistoryMapper;
import com.you.youbackend.album.mapper.AlbumMomentHistoryMapper;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.album.model.AlbumOrganizeMode;
import com.you.youbackend.album.request.CreateAlbumRequest;
import com.you.youbackend.album.request.AddAlbumMomentsRequest;
import com.you.youbackend.album.request.RemoveAlbumMomentsRequest;
import com.you.youbackend.album.request.UpdateAlbumRequest;
import com.you.youbackend.album.response.AlbumResponse;
import com.you.youbackend.album.response.AlbumListItem;
import com.you.youbackend.album.model.AlbumListCursor;
import com.you.youbackend.album.model.AlbumMomentCursor;
import com.you.youbackend.album.model.AlbumMomentCount;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.response.MomentResponse;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.mapper.MomentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 时光创建、信息维护、删除及访问权限查询。 */
@Log4j2
@Service
@RequiredArgsConstructor
public class AlbumService {
    private final AlbumMapper albumMapper;
    private final AlbumMomentMapper albumMomentMapper;
    private final MomentMapper momentMapper;
    private final MediaService mediaService;
    private final MomentService momentService;
    private final AlbumHistoryMapper albumHistoryMapper;
    private final AlbumMomentHistoryMapper albumMomentHistoryMapper;
    private final AlbumMomentReader albumMomentReader;

    /**
     * 整批校验后新增、保留或恢复关联，关联及修改时间在同一事务中保存。
     * @param albumId 有效时光 ID，不存在返回 404，非本人返回 403
     * @param request 已通过非空及 UUID 校验的请求
     * @param userId 当前用户 ID，任一瞬间非本人或无效则整批返回 400
     * @return 最新时光信息及有效瞬间数量
     */
    @Transactional
    public AlbumResponse addAlbumMoments(UUID albumId, AddAlbumMomentsRequest request, UUID userId) {
        // 1. 普通查询校验时光归属，去重后整批校验本人有效瞬间，通过前不写入。
        Album album = requireOwnedAlbum(albumMapper.selectById(albumId), userId);
        List<UUID> ids = request.momentIds().stream().distinct().toList();
        validateOwnedMoments(ids, userId);
        // 2. 使用统一时间新增或恢复关联，已有效关联保持原样。
        LocalDateTime savedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        addOrRestoreAssociations(albumId, ids, savedAt);
        // 3. 刷新时光修改时间及完整响应，失败时全部回滚。
        AlbumResponse response = finishAssociationChange(album, userId, savedAt);
        log.info("已写入时光瞬间添加，albumId={}，userId={}，submittedCount={}", albumId, userId, ids.size());
        return response;
    }

    /**
     * 批量移出当前时光关联，原瞬间及其他时光关系保留，允许移出全部。
     * @param albumId 有效时光 ID，不存在返回 404，非本人返回 403
     * @param request 已通过非空及 UUID 校验的请求，未加入和已移出项视为完成
     * @param userId 当前登录用户 ID
     * @return 最新时光信息，关联移出与修改时间共同提交或回滚
     */
    @Transactional
    public AlbumResponse removeAlbumMoments(UUID albumId, RemoveAlbumMomentsRequest request, UUID userId) {
        // 1. 校验时光归属并合并重复 ID，不要求原瞬间仍有效或属于本人。
        Album album = requireOwnedAlbum(albumMapper.selectById(albumId), userId);
        List<UUID> ids = request.momentIds().stream().distinct().toList();
        // 2. 仅移出当前时光的匹配有效关联，已移出和不存在项自然跳过。
        LocalDateTime savedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        albumMomentMapper.removeSelectedAssociations(albumId, ids, savedAt);
        // 3. 即使全部已完成也刷新时光修改时间，重新统计有效数量。
        AlbumResponse response = finishAssociationChange(album, userId, savedAt);
        log.info("已写入时光瞬间移出，albumId={}，userId={}，submittedCount={}", albumId, userId, ids.size());
        return response;
    }

    /**
     * 全量修改本人时光展示信息，在同一事务内校验封面、保存并组装响应，不改变瞬间关联。
     * @param albumId 时光 ID，不存在或已删除返回 404，非本人返回 403
     * @param request 已完成必填、名称及介绍长度校验的请求，null 清空可空字段
     * @param userId 当前登录用户 ID
     * @return 保存后的完整信息，包含有效瞬间数及封面签名 URL
     */
    @Transactional
    public AlbumResponse updateAlbum(UUID albumId, UpdateAlbumRequest request, UUID userId) {
        // 1. 先确认有效时光归属，再校验明确提交的封面为本人已就绪图片。
        Album album = requireOwnedAlbum(albumMapper.selectById(albumId), userId);
        String coverKey = requireCoverKey(request.coverFileId(), userId);
        // 2. 只保存可编辑展示字段，可空字段显式清空，不调整瞬间关联。
        updateAlbumContent(album, request);
        // 3. 读取当前有效瞬间数量，签名失败时由事务回滚本次信息修改。
        long count = albumMomentReader.countValidMoments(albumId, userId);
        AlbumResponse response = toResponse(album, coverKey, count);
        log.info("已写入时光信息修改，albumId={}，userId={}", albumId, userId);
        return response;
    }

    /**
     * 删除本人时光及其有效关联，两类写入共同提交或回滚，保留原瞬间和媒体。
     * @param albumId 时光 ID，不存在返回 404，本人已删除记录直接成功
     * @param userId 当前登录用户 ID，非所有者返回 403
     */
    @Transactional
    public void deleteAlbum(UUID albumId, UUID userId) {
        // 1. 包含软删除记录查询，先校验归属再判断幂等，避免他人记录被当作成功。
        var album = albumHistoryMapper.selectById(albumId);
        requireAlbumOwner(albumId, album == null ? null : album.getOwnerUserId(), userId);
        if (album.getRemovedAt() != null) {
            log.debug("时光已删除，跳过重复操作，albumId={}，userId={}", albumId, userId);
            return;
        }
        // 2. 使用同一时间标记主记录与尚有效的关联，详情查询随后通过软删除过滤拒绝访问。
        LocalDateTime deletedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        albumMapper.softDelete(albumId, deletedAt);
        albumMomentMapper.removeAssociations(albumId, deletedAt);
        log.info("已写入时光删除及关联移除，albumId={}，userId={}", albumId, userId);
    }

    /**
     * 分页浏览本人时光：按创建时间、ID 倒序读取，批量统计数量和加载封面，空时光正常返回。
     * @param userId 当前登录用户 ID
     * @param request 已校验分页请求，游标为 null 时读取首屏
     * @return 卡片及结构化游标，末页和空页的 nextCursor 为 null
     */
    @Transactional(readOnly = true)
    public ResultPage<AlbumListItem, AlbumListCursor> browseAlbums(UUID userId, PageRequest<AlbumListCursor> request) {
        // 1. 按结构化边界读取页大小加一条时光，额外记录只用于判断下一页。
        List<Album> rows = queryAlbums(userId, request);
        if (rows.isEmpty()) {
            log.debug("浏览本人时光，userId={}，itemCount=0，hasMore=false", userId);
            return new ResultPage<>(List.of(), null, false);
        }
        boolean hasMore = rows.size() > request.limit();
        List<Album> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        // 2. 仅为实际返回的本页时光批量统计有效瞬间和查询封面文件。
        List<AlbumListItem> items = loadAlbumItems(page, userId);
        // 3. 用最后一条实际返回记录生成下一页位置，空时光和失效封面不影响分页。
        Album last = page.get(page.size() - 1);
        AlbumListCursor next = hasMore ? new AlbumListCursor(last.getCreatedAt().toInstant(ZoneOffset.UTC), last.getId()) : null;
        log.debug("浏览本人时光，userId={}，itemCount={}，hasMore={}", userId, items.size(), hasMore);
        return new ResultPage<>(items, next, hasMore);
    }

    /**
     * 浏览本人时光线：先校验时光归属，再按发生时间、发布时间、ID 正序分页，批量加载详情。
     * @param albumId 时光 ID，不存在或已删除返回 404，非本人返回 403
     * @param userId 当前登录用户 ID
     * @param request 已校验的分页请求，结构化游标为 null 时从最早瞬间开始
     * @return 与当前瞬间详情一致的响应和下一页游标，排除失效关联及无效瞬间
     */
    @Transactional(readOnly = true)
    public ResultPage<MomentResponse, AlbumMomentCursor> browseAlbumMoments(
            UUID albumId, UUID userId, PageRequest<AlbumMomentCursor> request) {
        // 1. 每次分页均检查时光存在和归属，通过后才能查询关联瞬间。
        requireOwnedAlbum(albumMapper.selectById(albumId), userId);
        List<Moment> rows = queryTimeline(albumId, userId, request);
        if (rows.isEmpty()) {
            log.debug("浏览时光瞬间，albumId={}，userId={}，itemCount=0，hasMore=false", albumId, userId);
            return new ResultPage<>(List.of(), null, false);
        }
        // 2. 裁剪用于判断下一页的额外记录，批量加载实际页的完整图片及标签。
        boolean hasMore = rows.size() > request.limit();
        List<Moment> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        List<MomentResponse> items = momentService.loadOwnedResponses(page, userId);
        // 3. 三个排序字段共同确定位置，同一发生时间、发布时间的瞬间也不重不漏。
        Moment last = page.get(page.size() - 1);
        AlbumMomentCursor next = hasMore ? new AlbumMomentCursor(last.getMemoryTime().toInstant(ZoneOffset.UTC),
                last.getCreatedAt().toInstant(ZoneOffset.UTC), last.getId()) : null;
        log.debug("浏览时光瞬间，albumId={}，userId={}，itemCount={}，hasMore={}", albumId, userId, items.size(), hasMore);
        return new ResultPage<>(items, next, hasMore);
    }

    /**
     * 手动创建时光：整理瞬间 ID，校验封面及所有瞬间，再保存主记录和批量关联，返回信息。
     * 主记录及关联在同一事务中写入，校验或后续操作失败全部回滚；允许无初始瞬间。
     * @param request 已由 MVC 完成名称、介绍长度、UUID 格式及必填校验的请求
     * @param userId 当前登录用户，作为时光所有者及资源校验依据
     * @return 已保存的时光信息，含有效瞬间数量和封面签名 URL
     * @throws BusinessException 封面不可用或任一瞬间不属于本人、已删除或不存在
     */
    @Transactional
    public AlbumResponse createAlbum(CreateAlbumRequest request, UUID userId) {
        // 1. 合并重复瞬间 ID，并在写入前确认封面和全部瞬间均为本人有效资源。
        List<UUID> momentIds = request.momentIds().stream().distinct().toList();
        String coverKey = requireCoverKey(request.coverFileId(), userId);
        validateOwnedMoments(momentIds, userId);
        // 2. 统一本次写入时间，保证时光主记录和初始关联的审计时间一致。
        LocalDateTime savedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

        // 3. 在当前事务内先保存时光，再批量保存关联；任一步失败均整体回滚。
        Album album = saveNewAlbum(request, userId, savedAt);
        saveMomentAssociations(album.getId(), momentIds, savedAt);
        // 4. 按有效本人瞬间统计数量，复用已校验的封面对象键生成响应。
        long momentCount = albumMomentReader.countValidMoments(album.getId(), userId);
        AlbumResponse response = toResponse(album, coverKey, momentCount);
        log.info("已写入时光，albumId={}，userId={}，associationCount={}", album.getId(), userId, momentIds.size());
        return response;
    }

    /**
     * 查看时光信息：先检查存在和归属，再统计有效的本人瞬间、读取有效封面并组装响应。
     * @param albumId 时光 ID，软删除记录不可访问
     * @param userId 当前登录用户 ID
     * @return 时光信息，不包含瞬间列表；封面失效时 URL 为 null
     * @throws BusinessException 时光不存在、已删除或非当前用户所有
     */
    @Transactional(readOnly = true)
    public AlbumResponse getAlbum(UUID albumId, UUID userId) {
        // 1. 先确认时光存在且属于当前用户，通过后才加载详情相关资源。
        Album album = requireOwnedAlbum(albumMapper.selectById(albumId), userId);
        // 2. 排除已移除关联、已删除或非本人瞬间，并读取当前仍有效的封面。
        long momentCount = albumMomentReader.countValidMoments(albumId, userId);
        String coverKey = findCoverKey(album.getCoverFileId(), userId);
        log.debug("查询时光信息，albumId={}，userId={}，momentCount={}", albumId, userId, momentCount);
        // 3. 组装详情；封面失效时保留文件 ID，签名 URL 返回 null。
        return toResponse(album, coverKey, momentCount);
    }

    /**
     * 批量分类并保存关联，保留历史记录身份和已有效关联的审计字段。
     * @param albumId 本人有效时光 ID
     * @param ids 非空、去重且已校验的瞬间 ID
     * @param savedAt 本次统一 UTC 毫秒时间
     */
    private void addOrRestoreAssociations(UUID albumId, List<UUID> ids, LocalDateTime savedAt) {
        // 1. 包含历史关联批量读取，按瞬间 ID 识别已有关系，避免逐项查询。
        Map<UUID, AlbumMomentHistory> existing = albumMomentHistoryMapper.selectIncludingDeleted(albumId, ids).stream()
                .collect(Collectors.toMap(AlbumMomentHistory::getMomentId, row -> row));
        List<UUID> added = ids.stream().filter(id -> !existing.containsKey(id)).toList();
        List<UUID> restored = ids.stream().filter(id -> existing.containsKey(id)
                && existing.get(id).getRemovedAt() != null).toList();
        // 2. 新关系复用批量构造及插入，历史关系清空移出时间，有效关系不写入。
        saveMomentAssociations(albumId, added, savedAt);
        if (!restored.isEmpty()) {
            albumMomentHistoryMapper.restoreAssociations(albumId, restored, savedAt);
        }
    }

    /**
     * 保存关联调整后的修改时间并加载最新信息，不重复查询时光主记录。
     * @param album 已确认归属的完整时光实体
     * @param userId 当前所有者 ID
     * @param savedAt 与关联变更一致的 UTC 时间
     * @return 数量重新统计、封面失效时 URL 降级为 null 的完整响应
     */
    private AlbumResponse finishAssociationChange(Album album, UUID userId, LocalDateTime savedAt) {
        // 1. 只更新时间；若时光已不可更新，抛出异常回滚关联变更。
        if (albumMapper.touch(album.getId(), savedAt) == 0) {
            throw BusinessException.notFound("ALBUM_NOT_FOUND", "时光不存在或已删除");
        }
        album.setUpdatedAt(savedAt);
        // 2. 按有效本人瞬间统计，并沿用现有封面降级与签名规则。
        long count = albumMomentReader.countValidMoments(album.getId(), userId);
        return toResponse(album, findCoverKey(album.getCoverFileId(), userId), count);
    }

    /**
     * 保存已校验的可编辑字段，不覆盖其他时光设置。
     * @param album 已确认归属的有效完整实体
     * @param request 已完成字段及封面校验的全量请求
     */
    private void updateAlbumContent(Album album, UpdateAlbumRequest request) {
        // 1. 将全量提交的最终内容写入实体，并统一使用 UTC 毫秒修改时间。
        album.setName(request.name());
        album.setDescription(request.description());
        album.setCoverFileId(request.coverFileId());
        album.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS));
        // 2. 若校验后时光被并发删除，拒绝返回未实际保存的成功响应。
        if (albumMapper.updateContent(album) == 0) {
            throw BusinessException.notFound("ALBUM_NOT_FOUND", "时光不存在或已删除");
        }
    }

    /**
     * 查询本人时光页，将游标时间转换为数据库 UTC 毫秒时间。
     * @param userId 当前用户 ID
     * @param request 已通过字段校验的分页请求
     * @return 最多页大小加一条的完整时光实体
     */
    private List<Album> queryAlbums(UUID userId, PageRequest<AlbumListCursor> request) {
        // 首屏和后续页各用一条固定查询，Service 只决定查询入口。
        AlbumListCursor cursor = request.cursor();
        int rowCount = request.limit() + 1;
        if (cursor == null) {
            return albumMapper.selectFirstPage(userId, rowCount);
        }
        return albumMapper.selectNextPage(userId, toCursorTime(cursor.createdAt()), cursor.id(), rowCount);
    }

    /**
     * 查询有效时光线页，转换成组时间边界。
     * @param albumId 已校验存在及归属的时光 ID
     * @param userId 当前用户 ID
     * @param request 已通过字段校验的分页请求
     * @return 最多页大小加一条的完整瞬间实体
     */
    private List<Moment> queryTimeline(UUID albumId, UUID userId, PageRequest<AlbumMomentCursor> request) {
        // 首屏不传边界；后续页将三个游标字段转换后传给固定查询。
        AlbumMomentCursor cursor = request.cursor();
        int rowCount = request.limit() + 1;
        if (cursor == null) {
            return albumMomentReader.selectFirstTimelinePage(albumId, userId, rowCount);
        }
        return albumMomentReader.selectNextTimelinePage(albumId, userId,
                toCursorTime(cursor.memoryTime()), toCursorTime(cursor.createdAt()), cursor.id(), rowCount);
    }

    /**
     * 转换游标时间，超出 MySQL DATETIME 年份范围返回 400。
     * @param value 已由请求边界解析且保证非空的时间点
     * @return UTC 毫秒精度的数据库时间
     */
    private LocalDateTime toCursorTime(Instant value) {
        // 先验证数据库可表示范围，避免客户端构造极端时间触发数据库异常。
        try {
            LocalDateTime time = LocalDateTime.ofInstant(value, ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
            if (time.getYear() >= 1000 && time.getYear() <= 9999) {
                return time;
            }
        } catch (DateTimeException exception) {
            throw BusinessException.invalidArgument("游标时间超出可查询范围");
        }
        throw BusinessException.invalidArgument("游标时间超出可查询范围");
    }

    /**
     * 批量加载本页卡片所需数量和封面，保持原分页顺序。
     * @param page 实际返回的非空完整时光列表，已按当前用户筛选
     * @param userId 当前所有者 ID
     * @return 空时光数量补 0、失效封面 URL 为 null 的卡片列表
     */
    private List<AlbumListItem> loadAlbumItems(List<Album> page, UUID userId) {
        // 1. 一次统计本页全部有效本人瞬间数量，避免逐个时光查库。
        Map<UUID, Long> counts = albumMomentReader.countValidMomentsByAlbum(
                page.stream().map(Album::getId).toList(), userId).stream()
                .collect(Collectors.toMap(AlbumMomentCount::albumId, AlbumMomentCount::momentCount));
        // 2. 合并非空封面 ID 后批量校验文件，未就绪、非本人和已删除文件不会返回对象键。
        Map<UUID, String> coverKeys = mediaService.findOwnedImageKeys(userId,
                page.stream().map(Album::getCoverFileId).filter(Objects::nonNull).distinct().toList());
        // 3. 按原顺序组装卡片；没有统计行的空时光补 0。
        return page.stream().map(album -> toListItem(album,
                album.getCoverFileId() == null ? null : coverKeys.get(album.getCoverFileId()),
                counts.getOrDefault(album.getId(), 0L))).toList();
    }

    /**
     * 组装本页时光卡片，不访问数据库。
     * @param album 本人有效时光的完整实体
     * @param coverKey 已校验的封面对象键，无封面或失效时为空
     * @param momentCount 有效本人瞬间数量，统计缺失由调用方补 0
     * @return 附带封面签名 URL 和 UTC 时间的卡片
     */
    private AlbumListItem toListItem(Album album, String coverKey, long momentCount) {
        // 有效封面按当前媒体规则签名，无封面或失效时返回 null，时间统一转换为 UTC。
        String coverUrl = coverKey == null ? null : mediaService.createReadUrl(coverKey);
        return new AlbumListItem(album.getId(), album.getName(), coverUrl, momentCount, album.getOrganizeMode(),
                album.getCreatedAt().toInstant(ZoneOffset.UTC), album.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    /**
     * 校验选定封面，复用媒体模块的就绪图片查询；不生成签名地址。
     * @param fileId 可空，null 表示不设置封面
     * @param userId 当前用户 ID
     * @return 有效封面的对象键；无封面为 null
     */
    private String requireCoverKey(UUID fileId, UUID userId) {
        // 先查询有效封面；仅在用户明确指定了不可用文件时拒绝本次写入。
        String key = findCoverKey(fileId, userId);
        if (fileId != null && key == null) {
            log.warn("时光封面校验失败，fileId={}，userId={}，reason=非本人有效图片", fileId, userId);
            throw BusinessException.badRequest("FILE_NOT_USABLE", "请选择已上传完成的本人封面图片");
        }
        return key;
    }

    /**
     * 读取本人已登记且未删除图片的对象键，缺失或失效不抛出业务错误，供详情降级展示。
     * @param fileId 可空的封面文件 ID
     * @param userId 文件所有者 ID
     * @return 有效对象键，无封面或文件失效时为 null
     */
    private String findCoverKey(UUID fileId, UUID userId) {
        // 未设置封面时跳过查询；文件已删除、未就绪或非本人图片时按无有效封面处理。
        return fileId == null ? null : mediaService.findOwnedImageKeys(userId, List.of(fileId)).get(fileId);
    }

    /**
     * 一次查询校验全部候选瞬间，任一无效则拒绝整次创建。
     * @param ids 已去重且非 null 的瞬间 ID 列表，允许为空
     * @param userId 当前用户 ID，所有瞬间须为本人发布
     */
    private void validateOwnedMoments(List<UUID> ids, UUID userId) {
        // 空时光无需查询；去重后的提交数量必须与查到的本人有效瞬间数量一致。
        if (!ids.isEmpty() && momentMapper.selectOwnedIds(userId, ids).size() != ids.size()) {
            log.warn("时光瞬间校验失败，userId={}，submittedCount={}，reason=存在非本人或无效瞬间", userId, ids.size());
            throw BusinessException.invalidArgument("请选择本人发布且未删除的瞬间");
        }
    }

    /**
     * 保存时光主记录，服务端填写 ID、所有者、manual 整理方式及审计字段。
     * @param request 已通过全部校验的请求
     * @param userId 当前用户 ID
     * @param savedAt 本次事务写入的 UTC 毫秒时间
     * @return 已插入的完整实体
     */
    private Album saveNewAlbum(CreateAlbumRequest request, UUID userId, LocalDateTime savedAt) {
        // 1. 服务端生成时光身份并绑定当前用户，名称等可编辑信息取自已校验请求。
        Album album = new Album();
        album.setId(UuidCreator.getTimeOrderedEpoch());
        album.setOwnerUserId(userId);
        album.setName(request.name());
        album.setDescription(request.description());
        album.setCoverFileId(request.coverFileId());
        // 2. 手动创建固定使用 manual，创建和修改时间使用本次统一时间。
        album.setOrganizeMode(AlbumOrganizeMode.manual);
        album.setCreatedAt(savedAt);
        album.setUpdatedAt(savedAt);
        // 3. 保存主记录，返回实体供关联创建和响应组装复用。
        albumMapper.insert(album);
        return album;
    }

    /**
     * 构造并批量保存初始关联，空列表跳过写入，所有记录参与创建事务。
     * @param albumId 新建时光 ID
     * @param momentIds 已去重且校验有效的瞬间 ID
     * @param savedAt 主记录与关联共用的 UTC 时间
     */
    private void saveMomentAssociations(UUID albumId, List<UUID> momentIds, LocalDateTime savedAt) {
        // 1. 允许创建空时光，未选瞬间时不执行关联写入。
        if (momentIds.isEmpty()) {
            return;
        }
        // 2. 为每个已去重的瞬间构造独立关联，统一使用主记录的审计时间。
        List<AlbumMoment> associations = momentIds.stream().map(momentId -> {
            AlbumMoment association = new AlbumMoment();
            association.setId(UuidCreator.getTimeOrderedEpoch());
            association.setAlbumId(albumId);
            association.setMomentId(momentId);
            association.setCreatedAt(savedAt);
            association.setUpdatedAt(savedAt);
            return association;
        }).toList();
        // 3. 批量写入全部关联，与时光主记录共同提交或回滚。
        albumMomentMapper.insertAssociations(associations);
    }

    /**
     * 检查记录存在及归属；访问和修改由查询过滤软删除，删除入口单独判断幂等。
     * @param album 查询结果，可空；删除入口允许传入软删除记录
     * @param userId 当前用户 ID
     * @return 原时光实体
     */
    private Album requireOwnedAlbum(Album album, UUID userId) {
        // 普通查询已过滤软删除，归属校验与删除入口共用。
        requireAlbumOwner(album == null ? null : album.getId(), album == null ? null : album.getOwnerUserId(), userId);
        return album;
    }

    /** 校验时光存在且属于当前用户，删除入口同样检查历史记录的归属。 */
    private void requireAlbumOwner(UUID albumId, UUID ownerId, UUID userId) {
        // 先区分不存在，再处理越权。
        if (ownerId == null) {
            throw BusinessException.notFound("ALBUM_NOT_FOUND", "时光不存在或已删除");
        }
        if (!userId.equals(ownerId)) {
            log.warn("时光访问被拒绝，albumId={}，userId={}，reason=非本人时光", albumId, userId);
            throw BusinessException.forbidden("仅所有者可以操作此时光");
        }
    }

    /**
     * 组装详情并为有效封面签名，不访问数据库；签名异常继续抛出，避免掩盖系统故障。
     * @param album 完整的已保存或查询实体，数据库时间为 UTC
     * @param coverKey 已校验的封面对象键，可空
     * @param momentCount 当前有效的本人瞬间数量
     * @return 时间转换为 Instant 的独立响应
     */
    private AlbumResponse toResponse(Album album, String coverKey, long momentCount) {
        // 1. 仅为有效封面生成临时地址，签名故障交由统一异常入口处理。
        String coverUrl = coverKey == null ? null : mediaService.createReadUrl(coverKey);
        // 2. 保留原封面 ID 和可空字段，将数据库 UTC 时间转换为接口时间点。
        return new AlbumResponse(album.getId(), album.getName(), album.getDescription(), album.getCoverFileId(),
                coverUrl, album.getOrganizeMode(), momentCount, album.getCreatedAt().toInstant(ZoneOffset.UTC),
                album.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
}
