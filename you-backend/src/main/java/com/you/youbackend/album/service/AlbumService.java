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
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.response.MomentSummaryResponse;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.mapper.MomentMapper;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

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
     * 整批校验后新增、保留或恢复关联，关联及修改时间在同一事务中保存。
     *
     * @param albumId 有效时光 ID，不存在返回 404，非本人返回 403
     * @param request 已通过非空及 UUID 校验的请求
     * @param userId  当前用户 ID，任一瞬间非本人或无效则整批返回 400
     * @return 最新时光信息及有效瞬间数量
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
        LocalDateTime savedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS); // TODO 考虑抽离
        // 5.1 创建准备写入 AlbumMoment 表的实体
        List<AlbumMoment> associations = newAssociations(albumId, momentIds, savedAt);
        // 5.2 写入或者恢复关联，保持幂等
        albumMomentMapper.insertOrRestoreAssociations(associations, savedAt);
        // 5.3 更新 Album 表的 updateAt
        albumMapper.updateUpdatedAtById(albumId, savedAt);
        log.info("已写入时光瞬间添加，albumId={}，userId={}，submittedCount={}", albumId, userId, momentIds.size());
        // 6. 获取该 Moment 的响应体，手动回显 updatedAt
        album.setUpdatedAt(savedAt);
        return convertToDetailedRespone(album);
    }

    // TODO 讨论方法命名
    private List<AlbumMoment> newAssociations(UUID albumId, List<UUID> momentIds, LocalDateTime savedAt) {
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
     * 批量移出当前时光关联，原瞬间及其他时光关系保留，允许移出全部。
     *
     * @param albumId 有效时光 ID，不存在返回 404，非本人返回 403
     * @param request 已通过非空及 UUID 校验的请求，未加入和已移出项视为完成
     * @param userId  当前登录用户 ID
     * @return 最新时光信息，关联移出与修改时间共同提交或回滚
     */
    // TODO 关于savedAt，可以考虑让mapper层自己生成？同样可以啥都不返回
    @Transactional
    public AlbumDetailedResponse removeAlbumMoments(UUID albumId, RemoveAlbumMomentsRequest request, UUID userId) {
        // 1. 获得已鉴权的 album
        Album album = getOwnedAlbum(albumId, userId);
        // 2. 拿到新的 momentId 集合，去重
        List<UUID> ids = request.momentIds().stream().distinct().toList();
        // 3. 移出当前时光的匹配有效关联，已移出和不存在项自然跳过。
        LocalDateTime savedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        albumMomentMapper.softDeleteByAlbumIdAndMomentIdIn(albumId, ids, savedAt);
        // 4. 更新 album 的更新时间。
        albumMapper.updateUpdatedAtById(albumId, savedAt);
        log.info("已写入时光瞬间移出，albumId={}，userId={}，submittedCount={}", albumId, userId, ids.size());
        // 5. 构造响应，手动回显
        album.setUpdatedAt(savedAt);
        return convertToDetailedRespone(album);
    }

    /**
     * 全量修改本人时光展示信息，在同一事务内校验封面、保存并组装响应，不改变瞬间关联。
     *
     * @param albumId 时光 ID，不存在或已删除返回 404，非本人返回 403
     * @param request 已完成必填、名称及介绍长度校验的请求，null 清空可空字段
     * @param userId  当前登录用户 ID
     * @return 保存后的完整信息，包含有效瞬间数及封面签名 URL
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
        album.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS));
        // 4. 落库
        albumMapper.updateDisplayInfo(album);
        log.info("已写入时光信息修改，albumId={}，userId={}", albumId, userId);
        // 5. 构造响应
        return convertToDetailedRespone(album);
    }

    /**
     * 删除本人时光及其有效关联，两类写入共同提交或回滚，保留原瞬间和媒体。
     *
     * @param albumId 时光 ID，不存在返回 404，本人已删除记录直接成功
     * @param userId  当前登录用户 ID，非所有者返回 403
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
        LocalDateTime deletedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        // 4.1 删除关联
        albumMomentMapper.softDeleteByAlbumId(albumId, deletedAt);
        // 4.2 删除本体
        albumMapper.softDeleteById(albumId, deletedAt);
        // 5. 发布事件：【一个时光已删除】
        events.publishEvent(new AlbumDeletedEvent(albumId, deletedAt));
        log.info("已写入时光删除及关联移除，albumId={}，userId={}", albumId, userId);
    }

    /**
     * 分页浏览本人时光：按创建时间、ID 倒序读取，批量统计数量和加载封面，空时光正常返回。
     *
     * @param userId  当前登录用户 ID
     * @param request 已校验分页请求，游标为 null 时读取首屏
     * @return 卡片及结构化游标，末页和空页的 nextCursor 为 null
     */
    @Transactional(readOnly = true)
    public ResultPage<AlbumSummaryResponse, AlbumListCursor> browseAlbums(UUID userId, PageRequest<AlbumListCursor> request) {
        // 1. 通过游标拿到数据库的 album 实体
        AlbumListCursor cursor = request.cursor();
        LocalDateTime cursorTime = (cursor == null) ? null : toCursorTime(cursor.createdAt());// TODO 和momentservice.toDatabaseMemoryTime一样的重复冗余
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
     * 浏览本人时光线：先校验时光归属，再按发生时间、发布时间、ID 正序分页，批量加载详情。
     *
     * @param albumId 时光 ID，不存在或已删除返回 404，非本人返回 403
     * @param userId  当前登录用户 ID
     * @param request 已校验的分页请求，结构化游标为 null 时从最早瞬间开始
     * @return 与当前瞬间详情一致的响应和下一页游标，排除失效关联及无效瞬间
     * TODO 应该返回 Summary
     */
    @Transactional(readOnly = true)
    public ResultPage<MomentSummaryResponse, AlbumMomentCursor> browseAlbumMoments(
            UUID albumId, UUID userId, PageRequest<AlbumMomentCursor> request) {
        // 1. 校验权限
        getOwnedAlbum(albumId, userId);
        // 2. album_moment 关联表 join moment 表，进行游标分页查询拿到 moment 实体，moment 失效不过滤
        AlbumMomentCursor cursor = request.cursor();
        LocalDateTime cursorTime = cursor == null ? null : toCursorTime(cursor.memoryTime()); // TODO 时间不统一是个问题
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
     * 手动创建时光：整理瞬间 ID，校验封面及所有瞬间，再保存主记录和批量关联，返回信息。
     * 主记录及关联在同一事务中写入，校验或后续操作失败全部回滚；允许无初始瞬间。
     *
     * @param request 已由 MVC 完成名称、介绍长度、UUID 格式及必填校验的请求
     * @param userId  当前登录用户，作为时光所有者及资源校验依据
     * @return 已保存的时光信息，含有效瞬间数量和封面签名 URL
     * @throws BusinessException 封面不可用或任一瞬间不属于本人、已删除或不存在
     */
    @Transactional
    public AlbumDetailedResponse createAlbum(CreateAlbumRequest request, UUID userId) {
        // 0. 校验文件是否有效
        mediaService.validateOwnedImage(request.coverFileId(), userId);
        // 1. 创建一个新的 Album
        LocalDateTime savedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        Album album = saveNewAlbum(request.name(), request.description(), request.coverFileId(), userId, savedAt);

        // 2. 保存关联
        List<UUID> momentIds = request.momentIds().stream().distinct().toList();
        List<AlbumMoment> associations = newAssociations(album.getId(), momentIds, savedAt);
        albumMomentMapper.insertOrRestoreAssociations(associations, savedAt);

        log.info("已写入时光，albumId={}，userId={}，associationCount={}", album.getId(), userId, momentIds.size());
        // 3. 构造响应
        return convertToDetailedRespone(album);
    }

    /**
     * 查看时光信息：先检查存在和归属，再统计有效的本人瞬间、读取有效封面并组装响应。
     *
     * @param albumId 时光 ID，软删除记录不可访问
     * @param userId  当前登录用户 ID
     * @return 时光信息，不包含瞬间列表；封面失效时 URL 为 null
     * @throws BusinessException 时光不存在、已删除或非当前用户所有
     */
    @Transactional(readOnly = true)
    public AlbumDetailedResponse getAlbum(UUID albumId, UUID userId) {
        // 1. 先确认时光存在且属于当前用户，通过后才加载详情相关资源。
        Album album = getOwnedAlbum(albumId, userId);
        // 2. 复用已授权时光的内容加载，所有者与共享入口分别完成各自的权限校验。
        return convertToDetailedRespone(album);
    }

    /**
     * 为已通过访问及归属校验的时光加载详情，调用方负责本人或共享来源的授权。
     *
     * @param album 已加载且已校验有效的完整时光实体
     * @return 有效瞬间数量与封面签名补齐后的详情，封面失效时保留文件 ID、URL 为 null
     */
    public AlbumDetailedResponse convertToDetailedRespone(Album album) {
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
                .createdAt(album.getCreatedAt().toInstant(ZoneOffset.UTC))
                .updatedAt(album.getUpdatedAt().toInstant(ZoneOffset.UTC))
                .build();
    }


    /**
     * 转换游标时间，超出 MySQL DATETIME 年份范围返回 400。
     *
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
     */
    public List<AlbumSummaryResponse> convertToSummaryResponse(List<Album> albums) {
        Assert.notEmpty(albums, "至少传入一个 moment");
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
                .createdAt(album.getCreatedAt().toInstant(ZoneOffset.UTC))
                .updatedAt(album.getUpdatedAt().toInstant(ZoneOffset.UTC))
                .build();
    }

    private Album saveNewAlbum(String name,
                               String description,
                               UUID coverFileId,
                               UUID userId,
                               LocalDateTime savedAt) {
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
     * 按 ID 查时光，并确认是不是当前用户自己的（默认不查已删除的）。
     *
     * <p>查不到抛 404，不是自己的抛 403；没有副作用。</p>
     *
     * @param albumId     相册 ID
     * @param ownerUserId 当前用户 ID
     * @return 属于该用户的相册实体
     * @throws BusinessException 相册不存在或不属于该用户时抛出
     */
    private Album getOwnedAlbum(UUID albumId, UUID ownerUserId) {
        return getOwnedAlbum(albumId, ownerUserId, false);
    }

    /**
     * 按 ID 查时光，并校验归属权（可选是否包含已删除的）。
     *
     * <p>流程：先查库拿相册 → 没查到记 debug 日志并抛未找到异常 → 归属人不是当前用户记 warn 日志并抛无权限异常 → 返回相册。
     * 只读查询，无副作用。</p>
     *
     * @param albumId          相册 ID
     * @param ownerUserId      当前用户 ID
     * @param includingDeleted 是否包含已逻辑删除的相册（{@code true} 包含，{@code false} 只查正常状态）
     * @return 校验通过的相册实体
     * @throws BusinessException 相册不存在（{@code ALBUM_NOT_FOUND}）或非本人所有时抛出
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
