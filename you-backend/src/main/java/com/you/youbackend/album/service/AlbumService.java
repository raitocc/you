package com.you.youbackend.album.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.entity.AlbumMoment;
import com.you.youbackend.album.event.AlbumDeletedEvent;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.album.model.AlbumOrganizeMode;
import com.you.youbackend.album.request.CreateAlbumRequest;
import com.you.youbackend.album.request.AddAlbumMomentsRequest;
import com.you.youbackend.album.request.RemoveAlbumMomentsRequest;
import com.you.youbackend.album.request.UpdateAlbumRequest;
import com.you.youbackend.album.response.AlbumDetailedResponse;
import com.you.youbackend.album.response.AlbumSummaryResponse;
import com.you.youbackend.album.model.AlbumListCursor;
import com.you.youbackend.album.model.AlbumMomentCursor;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.common.util.TimeUtils;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.response.MomentSummaryResponse;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.service.MediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.*;

/**
 * 时光创建、信息维护、删除及访问权限查询。
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class AlbumService {
    private final AlbumMapper albumMapper;
    private final AlbumMomentMapper albumMomentMapper;
    private final MediaService mediaService;
    private final MomentService momentService;
    private final ApplicationEventPublisher events;

    /**
     * 往本人时光里批量加瞬间：整批校验通过才写入，加过的保持原样（幂等），移出过的自动恢复。
     * 恢复保留原关联 ID 和创建时间；关联和时光的修改时间统一刷新。
     *
     * <p>流程：归属校验 → 瞬间 ID 去重 → 校验全为本人有效瞬间 → 批量新增或恢复关联 → 刷新时光修改时间 → 组装详情。
     * 副作用：album_moment、album 同事务写入，失败整体回滚。</p>
     *
     * @param albumId 时光 ID
     * @param request 已由 MVC 校验非空的请求；momentIds 重复 ID 合并
     * @param userId  当前登录用户 ID
     * @return 时光最新详情
     * @throws BusinessException 时光不存在、非本人（404/403），或任一瞬间不存在、已删除、非本人（400）
     */
    // TODO 可以完全什么都不返回，让前端自己再查一次！
    @Transactional
    public AlbumDetailedResponse addAlbumMoments(UUID albumId, AddAlbumMomentsRequest request, UUID userId) {
        // 1. 获得已鉴权的 album
        Album album = getOwnedAlbum(albumId, userId);
        // 2. 拿到需要放入此 album 的 momentId 集合，去重
        List<UUID> momentIds = request.momentIds().stream().distinct().toList();
        // 3. 校验，放入的 moment 必须均为本人的 moment
        momentService.validateOwnedMoments(momentIds, userId);
        // 4. 获取一个统一时间新增或恢复关联，已有效关联保持原样。
        Instant savedAt = TimeUtils.now();
        // 5.1 创建准备写入 AlbumMoment 表的实体
        List<AlbumMoment> associations = newAssociations(albumId, momentIds, savedAt);
        // 5.2 写入或者恢复关联，保持幂等
        albumMomentMapper.insertOrRestoreAssociations(associations, savedAt);
        // 5.3 更新 Album 表的 updateAt
        albumMapper.updateUpdatedAtById(albumId, savedAt);
        log.info("已写入时光瞬间添加，albumId={}，userId={}，submittedCount={}", albumId, userId, momentIds.size());
        // 6. 获取该 Moment 的响应体，手动回显 updatedAt
        album.setUpdatedAt(savedAt);
        return convertToDetailedResponse(album);
    }

    // TODO 讨论方法命名
    /**
     * 给一批瞬间 ID 拼待写入的时光关联实体，只填字段不入库。
     *
     * @param albumId   所属时光 ID
     * @param momentIds 已去重的瞬间 ID
     * @param savedAt   本次写入时间，UTC，同时给 createdAt 和 updatedAt
     * @return 关联实体列表，和 momentIds 一一对应；插库还是恢复由 Mapper 的 upsert 决定
     */
    private List<AlbumMoment> newAssociations(UUID albumId, List<UUID> momentIds, Instant savedAt) {
        return momentIds.stream().map(momentId -> {
            AlbumMoment association = new AlbumMoment();
            association.setId(UuidCreator.getTimeOrderedEpoch());
            association.setAlbumId(albumId);
            association.setMomentId(momentId);
            association.setCreatedAt(savedAt);
            association.setUpdatedAt(savedAt);
            return association;
        }).toList();
    }

    /**
     * 把指定瞬间移出本人时光：没加过和已移出的直接跳过，允许全部移出。原瞬间和其他时光的关联不受影响。
     *
     * <p>流程：归属校验 → 瞬间 ID 去重 → 软删本时光内的匹配关联 → 刷新时光修改时间 → 组装详情。
     * 副作用：album_moment、album 同事务写入，失败整体回滚。</p>
     *
     * @param albumId 时光 ID
     * @param request 已由 MVC 校验非空的请求；momentIds 重复 ID 合并
     * @param userId  当前登录用户 ID
     * @return 时光最新详情
     * @throws BusinessException 时光不存在或非本人时抛出（404/403）
     */
    // TODO 关于savedAt，可以考虑让mapper层自己生成？同样可以啥都不返回
    @Transactional
    public AlbumDetailedResponse removeAlbumMoments(UUID albumId, RemoveAlbumMomentsRequest request, UUID userId) {
        // 1. 获得已鉴权的 album
        Album album = getOwnedAlbum(albumId, userId);
        // 2. 拿到新的 momentId 集合，去重
        List<UUID> ids = request.momentIds().stream().distinct().toList();
        // 3. 移出当前时光的匹配有效关联，已移出和不存在项自然跳过。
        Instant savedAt = TimeUtils.now();
        albumMomentMapper.softDeleteByAlbumIdAndMomentIdIn(albumId, ids, savedAt);
        // 4. 更新 album 的更新时间。
        albumMapper.updateUpdatedAtById(albumId, savedAt);
        log.info("已写入时光瞬间移出，albumId={}，userId={}，submittedCount={}", albumId, userId, ids.size());
        // 5. 构造响应，手动回显
        album.setUpdatedAt(savedAt);
        return convertToDetailedResponse(album);
    }

    /**
     * 全量修改本人时光的展示信息（名称、介绍、封面），不动瞬间关联。
     *
     * <p>流程：归属校验 → 校验封面是本人可用图片 → 覆盖展示字段并记修改时间 → 落库 → 组装详情。
     * 副作用：只更新 album 表的展示字段和修改时间，同事务提交。</p>
     *
     * @param albumId 时光 ID
     * @param request 全量请求；description、coverFileId 传 null 表示清空
     * @param userId  当前登录用户 ID
     * @return 修改后的时光详情
     * @throws BusinessException 时光不存在、已删除或非本人（404/403），封面不可用（400）
     */
    @Transactional
    public AlbumDetailedResponse updateAlbum(UUID albumId, UpdateAlbumRequest request, UUID userId) {
        // 1. 获得已鉴权的 album
        Album album = getOwnedAlbum(albumId, userId);
        // 2. 验证封面文件的有效性
        mediaService.validateOwnedImage(request.coverFileId(), userId);
        // 3. 编辑可更新的字段
        album.setName(request.name());
        album.setDescription(request.description());
        album.setCoverFileId(request.coverFileId());
        album.setUpdatedAt(TimeUtils.now());
        // 4. 落库
        albumMapper.updateDisplayInfo(album);
        log.info("已写入时光信息修改，albumId={}，userId={}", albumId, userId);
        // 5. 构造响应
        return convertToDetailedResponse(album);
    }

    /**
     * 软删除本人时光，连同全部有效关联一起删；删过的再删直接返回（幂等）。原瞬间和媒体文件保留。
     *
     * <p>流程：归属校验（含已删除）→ 已删过就返回 → 软删全部有效关联 → 软删主记录 → 发“时光已删除”事件。
     * 副作用：album_moment、album 两处软删同事务提交，失败整体回滚。</p>
     *
     * @param albumId 时光 ID
     * @param userId  当前登录用户 ID
     * @throws BusinessException 时光不存在或非本人时抛出（404/403）
     */
    @Transactional
    public void deleteAlbum(UUID albumId, UUID userId) {
        // 1. 获得已鉴权的 album
        Album album = getOwnedAlbum(albumId, userId, true);
        // 2. 幂等
        if (album.getDeletedAt() != null) {
            return;
        }
        // 3. 记录当前的操作时间，统一标记时间
        Instant deletedAt = TimeUtils.now();
        // 4.1 删除关联
        albumMomentMapper.softDeleteByAlbumId(albumId, deletedAt);
        // 4.2 删除本体
        albumMapper.softDeleteById(albumId, deletedAt);
        // 5. 发布事件：【一个时光已删除】
        events.publishEvent(new AlbumDeletedEvent(albumId, deletedAt));
        log.info("已写入时光删除及关联移除，albumId={}，userId={}", albumId, userId);
    }

    /**
     * 分页浏览本人时光列表：按创建时间、ID 倒序查一页多一条，批量补数量和封面。
     *
     * <p>流程：查一页多一条 → 空页直接返回 → 本页批量签封面、数有效关联（没关联算 0）→ 组装卡片 → 多的那条只判断下一页，游标取本页最后一条。
     * 只读事务，无副作用。</p>
     *
     * @param userId  当前登录用户 ID，只查本人的有效时光
     * @param request 已校验分页请求；游标为空表示首屏
     * @return 卡片列表和下一页游标，末页游标为 null
     */
    // TODO 游标时间缺范围校验：超范围游标会直打数据库变 500，需补 400 参数校验
    @Transactional(readOnly = true)
    public ResultPage<AlbumSummaryResponse, AlbumListCursor> browseAlbums(UUID userId, PageRequest<AlbumListCursor> request) {
        // 1. 通过游标拿到数据库的 album 实体
        AlbumListCursor cursor = request.cursor();
        Instant cursorTime = (cursor == null) ? null : cursor.createdAt();
        UUID cursorId = (cursor == null) ? null : cursor.id();
        List<Album> rows = albumMapper.selectPageAfterCursor(userId, cursorTime, cursorId, request.limit() + 1);
        // 2. 空集合直接返回
        if (rows.isEmpty()) {
            return ResultPage.empty();
        }
        // 3. 将 Album 对象转换为 AlbumSummaryResponse对象
        List<Album> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        List<AlbumSummaryResponse> albumSummaryResponses = convertToSummaryResponse(page);
        // 4. 构造响应
        boolean hasMore = rows.size() > request.limit();
        log.debug("浏览本人时光，userId={}，albumCount={}，hasMore={}", userId, albumSummaryResponses.size(), hasMore);
        return ResultPage.toResultPage(
                albumSummaryResponses,
                hasMore,
                last -> new AlbumListCursor(last.createdAt(), last.id())
        );
    }

    /**
     * 分页浏览时光内的瞬间：按发生时间、ID 正序（从早到晚）查一页多一条，返回摘要卡片。
     * 只看本人瞬间和有效关联；瞬间的软删状态不过滤——业务保证删除瞬间时同步清理关联。
     *
     * <p>流程：时光归属校验 → 关联表 join 瞬间表查一页多一条 → 空页直接返回 → 本页转摘要卡片 → 多的那条只判断下一页，游标取本页最后一条。
     * 只读事务，无副作用。</p>
     *
     * @param albumId 时光 ID
     * @param userId  当前登录用户 ID
     * @param request 已校验分页请求；游标为空表示从最早的瞬间开始
     * @return 摘要卡片和下一页游标，末页游标为 null
     * @throws BusinessException 时光不存在、非本人时抛出（404/403）
     */
    // TODO 游标时间缺范围校验：超范围游标会直打数据库变 500，需补 400 参数校验
    @Transactional(readOnly = true)
    public ResultPage<MomentSummaryResponse, AlbumMomentCursor> browseAlbumMoments(
            UUID albumId, UUID userId, PageRequest<AlbumMomentCursor> request) {
        // 1. 校验权限
        getOwnedAlbum(albumId, userId);
        // 2. album_moment 关联表 join moment 表，进行游标分页查询拿到 moment 实体，moment 失效不过滤
        AlbumMomentCursor cursor = request.cursor();
        Instant cursorTime = cursor == null ? null : cursor.memoryTime();
        UUID cursorId = (cursor == null) ? null : cursor.id();
        List<Moment> rows = albumMomentMapper.selectPageByAlbumIdAndUserIdAfterCursor(albumId, userId, cursorTime, cursorId, request.limit() + 1); // 不知道叫什么名字比较好，也不知道放在momentmapper里好还是albummapper里好
        // 3. 转换为 summary( TODO 不知道为什么旧代码是Detailed)
        if (rows.isEmpty()) {
            return ResultPage.empty();
        }
        List<Moment> page = rows.subList(0, Math.min(rows.size(), request.limit()));
        List<MomentSummaryResponse> momentSummaryResponses = momentService.convertToSummaryResponse(page);
        // 4. 构造响应
        boolean hasMore = rows.size() > request.limit();
        return ResultPage.toResultPage(
                momentSummaryResponses,
                hasMore,
                last -> new AlbumMomentCursor(last.memoryTime(), last.id())
        );
    }

    /**
     * 手动创建时光：校验封面和初始瞬间，保存主记录和关联，返回完整信息。
     *
     * <p>流程：校验封面是本人可用图片 → 生成主记录（整理模式固定 manual）→ 瞬间 ID 去重并校验全为本人有效瞬间 → 批量写关联 → 组装详情。
     * 副作用：album、album_moment 两表同事务写入，失败整体回滚。</p>
     *
     * @param request 已由 MVC 校验的请求；momentIds 重复 ID 合并
     * @param userId  当前登录用户 ID，作所有者和图片、瞬间归属校验用
     * @return 新时光详情，含有效瞬间数和封面地址
     * @throws BusinessException 封面不是本人可用图片（400），或任一瞬间不存在、已删除、非本人（400）
     */
    @Transactional
    public AlbumDetailedResponse createAlbum(CreateAlbumRequest request, UUID userId) {
        // 0. 校验文件是否有效
        mediaService.validateOwnedImage(request.coverFileId(), userId);
        // 1. 创建一个新的 Album
        Instant savedAt = TimeUtils.now();
        Album album = saveNewAlbum(request.name(), request.description(), request.coverFileId(), userId, savedAt);
        // 2. 有初始的关联就关联，没有则跳过
        List<UUID> momentIds = request.momentIds().stream().distinct().toList();
        if (!request.momentIds().isEmpty()){
            // 先校验权限，再保存关联
            momentService.validateOwnedMoments(momentIds, userId);
            List<AlbumMoment> associations = newAssociations(album.getId(), momentIds, savedAt);
            albumMomentMapper.insertOrRestoreAssociations(associations, savedAt);

        }
        log.info("已写入时光，albumId={}，userId={}，associationCount={}", album.getId(), userId, momentIds.size());
        // 3. 构造响应
        return convertToDetailedResponse(album);
    }

    /**
     * 查看本人时光信息（不含瞬间列表）。
     *
     * <p>流程：存在和归属校验 → 数有效关联、签封面地址 → 组装详情。
     * 只读事务，无副作用。</p>
     *
     * @param albumId 时光 ID，软删除的查不到
     * @param userId  当前登录用户 ID
     * @return 时光详情；封面为空或已失效时 URL 为 null，文件 ID 保留
     * @throws BusinessException 时光不存在、已删除或非本人时抛出（404/403）
     */
    @Transactional(readOnly = true)
    public AlbumDetailedResponse getAlbum(UUID albumId, UUID userId) {
        // 1. 先确认时光存在且属于当前用户，通过后才加载详情相关资源。
        Album album = getOwnedAlbum(albumId, userId);
        // 2. 复用已授权时光的内容加载，所有者与共享入口分别完成各自的权限校验。
        return convertToDetailedResponse(album);
    }

    /**
     * 组装时光详情：数有效关联当瞬间数、签封面地址，不改传入实体。授权由调用方做完。
     *
     * <p>只读查询，无副作用。</p>
     *
     * @param album 已通过访问校验的完整时光实体
     * @return 详情响应；封面为空或已失效时 URL 为 null，文件 ID 保留
     */
    public AlbumDetailedResponse convertToDetailedResponse(Album album) {
        // 1. 统计有效瞬间
        long momentCount = albumMomentMapper.countMomentsByAlbumId(album.getId());
        String coverUrl = mediaService.getReadUrl(album.getCoverFileId());
        // 2. 使用既有响应转换和媒体签名规则，原实体保持只读。
        return AlbumDetailedResponse.builder()
                .id(album.getId())
                .name(album.getName())
                .description(album.getDescription())
                .coverFileId(album.getCoverFileId())
                .coverUrl(coverUrl)
                .organizeMode(album.getOrganizeMode())
                .momentCount(momentCount)
                .createdAt(album.getCreatedAt())
                .updatedAt(album.getUpdatedAt())
                .build();
    }

    /**
     * 把一页时光批量组装成卡片，顺序和入参一致。
     *
     * <p>流程：批量签封面地址 → 批量数有效关联（没关联的补 0）→ 逐条拼卡片。
     * 只读查询，无副作用。</p>
     *
     * @param albums 非空的时光实体列表，调用方保证非空
     * @return 与入参同序等长的卡片；无封面或封面失效时 URL 为 null
     * @throws IllegalArgumentException albums 为 null 或空（属服务端缺陷，转 500）
     */
    public List<AlbumSummaryResponse> convertToSummaryResponse(List<Album> albums) {
        Assert.notEmpty(albums, "至少传入一个 album");
        // 1. 拿到所有 fileId 的签名 url
        List<UUID> coverFileIds = albums.stream().map(Album::getCoverFileId).toList();
        Map<UUID, String> coverUrlsByFileId = mediaService.getReadUrls(coverFileIds);
        // 2. 拿到所有 album 的 moment 数量
        List<UUID> albumIds = albums.stream().map(Album::getId).toList();
        Map<UUID, Long> momentCountByMomentId = albumMomentMapper.countMomentsByAlbumIds(albumIds);
        // 3. 构造响应
        List<AlbumSummaryResponse> albumSummaryResponses = new ArrayList<>();
        for (Album album : albums) {
            UUID albumId = album.getId();
            UUID coverFileId = album.getCoverFileId();
            String coverUrl = coverUrlsByFileId.get(coverFileId);
            long momentCount = momentCountByMomentId.get(albumId);
            var summary = convertToSummaryResponse(album, coverUrl, momentCount);
            albumSummaryResponses.add(summary);
        }
        return albumSummaryResponses;
    }

    /**
     * 拼单条时光卡片，不查库，封面地址和数量由调用方算好传进来。
     *
     * @param album       时光实体，用到 ID、名称、整理模式、创建和修改时间
     * @param coverUrl    已签名的封面地址，无封面或失效时为 null
     * @param momentCount 该时光的有效关联数
     * @return 卡片响应，时间为 UTC Instant
     */
    private AlbumSummaryResponse convertToSummaryResponse(
            Album album,
            String coverUrl,
            long momentCount
    ) {
        return AlbumSummaryResponse.builder()
                .id(album.getId())
                .name(album.getName())
                .coverUrl(coverUrl)
                .momentCount(momentCount)
                .organizeMode(album.getOrganizeMode())
                .createdAt(album.getCreatedAt())
                .updatedAt(album.getUpdatedAt())
                .build();
    }

    /**
     * 组装并插入新时光主记录：服务端生成 UUIDv7 ID，整理模式固定 manual，审计时间用统一的保存时间。
     *
     * <p>副作用：往 album 表插一行，跟随外层事务。</p>
     *
     * @param name        已校验的名称
     * @param description 已校验的介绍，null 表示未设置
     * @param coverFileId 已确认可用的封面图片 ID，null 表示无封面
     * @param userId      所有者 ID
     * @param savedAt     本次写入时间，UTC，同时给 createdAt 和 updatedAt
     * @return 插入后的时光实体，供关联创建和响应组装复用
     */
    private Album saveNewAlbum(String name,
                               String description,
                               UUID coverFileId,
                               UUID userId,
                               Instant savedAt) {
        // 1. 服务端生成时光身份并绑定当前用户，名称等可编辑信息取自已校验请求。
        Album album = new Album();
        album.setId(UuidCreator.getTimeOrderedEpoch());
        album.setOwnerUserId(userId);
        album.setName(name);
        album.setDescription(description);
        album.setCoverFileId(coverFileId);
        // 2. 手动创建固定使用 manual，创建和修改时间使用本次统一时间。
        album.setOrganizeMode(AlbumOrganizeMode.manual);
        album.setCreatedAt(savedAt);
        album.setUpdatedAt(savedAt);
        // 3. 保存主记录，返回实体供关联创建和响应组装复用。
        albumMapper.insert(album);
        return album;
    }


    /**
     * 按 ID 查时光，并确认是当前用户自己的（默认不查已删除的）。
     *
     * <p>查不到抛 404，不是自己的抛 403；只读查询，无副作用。</p>
     *
     * @param albumId     时光 ID
     * @param ownerUserId 当前用户 ID
     * @return 属于该用户的时光实体
     * @throws BusinessException 时光不存在或不属于该用户时抛出
     */
    private Album getOwnedAlbum(UUID albumId, UUID ownerUserId) {
        return getOwnedAlbum(albumId, ownerUserId, false);
    }

    /**
     * 按 ID 查时光，并校验归属权（可选是否包含已删除的）。
     *
     * <p>流程：查库拿时光 → 没查到记 debug 日志并抛未找到异常 → 不是当前用户记 warn 日志并抛无权限异常 → 返回时光。
     * 只读查询，无副作用。</p>
     *
     * @param albumId          时光 ID
     * @param ownerUserId      当前用户 ID
     * @param includingDeleted 是否包含已逻辑删除的时光（{@code true} 包含，{@code false} 只查正常状态）
     * @return 校验通过的时光实体
     * @throws BusinessException 时光不存在（{@code ALBUM_NOT_FOUND}）或非本人所有时抛出
     */
    private Album getOwnedAlbum(UUID albumId, UUID ownerUserId, boolean includingDeleted) {
        Album album = null;
        if (includingDeleted) {
            album = albumMapper.selectIncludingDeletedById(albumId);
        } else {
            album = albumMapper.selectById(albumId);
        }
        if (album == null) {
            log.debug("时光查询未命中，userId={}", ownerUserId);
            throw BusinessException.notFound("ALBUM_NOT_FOUND", "时光不存在");
        }
        if (!ownerUserId.equals(album.getOwnerUserId())) {
            log.warn("时光访问被拒绝，albumId={}，userId={}，reason=非本人时光", album.getId(), ownerUserId);
            throw BusinessException.forbidden("仅作者可以操作此时光");
        }
        return album;
    }
}
