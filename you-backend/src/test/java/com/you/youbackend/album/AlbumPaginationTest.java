package com.you.youbackend.album;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.album.controller.AlbumController;
import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.mapper.AlbumHistoryMapper;
import com.you.youbackend.album.mapper.AlbumMomentHistoryMapper;
import com.you.youbackend.album.service.AlbumMomentReader;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.album.model.AlbumListCursor;
import com.you.youbackend.album.model.AlbumMomentCursor;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.exception.GlobalExceptionHandler;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.common.response.ResultPage;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.service.MomentService;
import org.junit.jupiter.api.Test;
import com.you.youbackend.common.request.PageQueryArgumentResolver;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 分页 MVC 契约及批量加载边界，数据库排序与软删除在集成测试中验证。 */
class AlbumPaginationTest {
    private final UUID user = UUID.randomUUID();
    private final UUID id = UuidCreator.getTimeOrderedEpoch();
    private final AlbumService controllerService = mock(AlbumService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AlbumController(controllerService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PageQueryArgumentResolver(new JsonMapper())).build();
    private final AlbumMapper albums = mock(AlbumMapper.class);
    private final AlbumMomentMapper associations = mock(AlbumMomentMapper.class);
    private final MomentMapper moments = mock(MomentMapper.class);
    private final MediaService media = mock(MediaService.class);
    private final MomentService details = mock(MomentService.class);
    private final AlbumHistoryMapper albumHistory = mock(AlbumHistoryMapper.class);
    private final AlbumMomentHistoryMapper associationHistory = mock(AlbumMomentHistoryMapper.class);
    private final AlbumMomentReader reader = mock(AlbumMomentReader.class);
    private final AlbumService service = new AlbumService(albums, associations, moments, media, details, albumHistory, associationHistory, reader);

    @Test
    void firstPagesDefaultTo20AndReturnStructuredCursor() throws Exception {
        // 私有时光直接使用资源集合路径，首屏省略游标和数量。
        var cursor = new AlbumListCursor(Instant.parse("2026-01-01T00:00:00Z"), id);
        when(controllerService.browseAlbums(eq(user), any())).thenReturn(new ResultPage<>(List.of(), cursor, true));
        mvc.perform(get("/albums").requestAttr("currentUserId", user))
                .andExpect(status().isOk()).andExpect(jsonPath("data.nextCursor.id").value(id.toString()));
        verify(controllerService).browseAlbums(user, new PageRequest<>(null, 20));
    }

    @Test
    void bindsBothCursorTypesAndOffsetTimes() throws Exception {
        // 同一解析器支持时光列表和时光线的两种游标类型。
        mvc.perform(get("/albums").requestAttr("currentUserId", user)
                .param("cursor.createdAt", "2026-01-01T08:00:00+08:00").param("cursor.id", id.toString()).param("limit", "5"))
                .andExpect(status().isOk());
        verify(controllerService).browseAlbums(user, new PageRequest<>(new AlbumListCursor(
                Instant.parse("2026-01-01T00:00:00Z"), id), 5));
        mvc.perform(get("/albums/{id}/moments", id).requestAttr("currentUserId", user)
                .param("cursor.memoryTime", "2025-01-01T00:00:00Z").param("cursor.createdAt", "2026-01-01T00:00:00Z")
                .param("cursor.id", id.toString())).andExpect(status().isOk());
        verify(controllerService).browseAlbumMoments(id, user, new PageRequest<>(new AlbumMomentCursor(
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), id), 20));
    }

    @Test
    void rejectsInvalidLimitsCursorFieldsAndStringCursorsBeforeBusiness() throws Exception {
        // 两种游标均级联校验，数量越界、字符串游标和不完整时间边界统一拒绝。
        for (String path : List.of("/albums", "/albums/" + id + "/moments")) {
            for (String limit : List.of("0", "101", "bad")) {
                mvc.perform(get(path).param("limit", limit)).andExpect(status().isBadRequest());
            }
            mvc.perform(get(path).param("cursor", "opaque")).andExpect(status().isBadRequest());
            mvc.perform(get(path).param("cursor.createdAt", "2026-01-01T00:00:00Z"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(path).param("cursor.createdAt", "bad").param("cursor.id", id.toString()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/albums/{id}/moments", id).param("cursor.createdAt", "2026-01-01T00:00:00Z")
                .param("cursor.id", id.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(controllerService);
    }

    @Test
    void controllerUsesExistingErrorsForOwnershipAndMissingAlbum() throws Exception {
        // 私有资源仍需阻止直接猜测他人 ID，详情错误沿用既有契约。
        when(controllerService.browseAlbumMoments(eq(id), eq(user), any())).thenThrow(BusinessException.forbidden("非本人"));
        mvc.perform(get("/albums/{id}/moments", id).requestAttr("currentUserId", user))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("FORBIDDEN"));
        when(controllerService.browseAlbumMoments(eq(id), eq(user), any())).thenThrow(BusinessException.notFound("ALBUM_NOT_FOUND", "不存在"));
        mvc.perform(get("/albums/{id}/moments", id).requestAttr("currentUserId", user)).andExpect(status().isNotFound());
    }

    @Test
    void extraAlbumRowDoesNotLoadCoverOrCounts() {
        // 多查的第二条仅用于 hasMore，加载封面和数量时只传实际返回的第一条。
        Album first = album(id), extra = album(UuidCreator.getTimeOrderedEpoch());
        UUID cover = UUID.randomUUID();
        first.setCoverFileId(cover);
        extra.setCoverFileId(UUID.randomUUID());
        when(albums.selectFirstPage(user, 2)).thenReturn(List.of(first, extra));
        when(reader.countValidMomentsByAlbum(List.of(id), user)).thenReturn(List.of());
        when(media.findOwnedImageKeys(user, List.of(cover))).thenReturn(Map.of());
        var result = service.browseAlbums(user, new PageRequest<>(null, 1));
        assertEquals(1, result.items().size());
        assertEquals(id, result.nextCursor().id());
        verify(reader).countValidMomentsByAlbum(List.of(id), user);
        verify(media).findOwnedImageKeys(user, List.of(cover));
    }

    @Test
    void emptyPageSkipsAllAssociationLoads() {
        // 没有实际返回记录时，不执行分组数量、封面或瞬间详情加载。
        when(albums.selectFirstPage(user, 21)).thenReturn(List.of());
        var result = service.browseAlbums(user, new PageRequest<>(null, null));
        assertTrue(result.items().isEmpty());
        assertNull(result.nextCursor());
        assertFalse(result.hasMore());
        verifyNoInteractions(associations, media, details);
    }

    @Test
    void rejectsOutOfDatabaseRangeTimesBeforePageQuery() {
        // Instant 能表示的极端时间不可直接交给 MySQL，业务层统一转换为参数错误。
        assertThrows(BusinessException.class, () -> service.browseAlbums(user,
                new PageRequest<>(new AlbumListCursor(Instant.MIN, id), 20)));
        assertThrows(BusinessException.class, () -> service.browseAlbums(user,
                new PageRequest<>(new AlbumListCursor(Instant.parse("0999-01-01T00:00:00Z"), id), 20)));
        when(albums.selectById(id)).thenReturn(album(id));
        assertThrows(BusinessException.class, () -> service.browseAlbumMoments(id, user,
                new PageRequest<>(new AlbumMomentCursor(Instant.parse("2026-01-01T00:00:00Z"), Instant.MAX, id), 20)));
        verify(albums, never()).selectFirstPage(any(), anyInt());
        verify(albums, never()).selectNextPage(any(), any(), any(), anyInt());
        verifyNoInteractions(associations);
    }

    @Test
    void albumCursorUsesNextPageQueryWithUtcBoundary() {
        // 有游标时只调用后续页查询，保留创建时间、ID 的原边界。
        Instant createdAt = Instant.parse("2026-01-01T08:00:00+08:00");
        LocalDateTime utc = LocalDateTime.parse("2026-01-01T00:00:00");
        when(albums.selectNextPage(user, utc, id, 3)).thenReturn(List.of());

        var result = service.browseAlbums(user, new PageRequest<>(new AlbumListCursor(createdAt, id), 2));

        assertTrue(result.items().isEmpty());
        verify(albums).selectNextPage(user, utc, id, 3);
        verify(albums, never()).selectFirstPage(any(), anyInt());
    }

    @Test
    void timelineSelectsFirstOrNextQueryWithoutChangingCursorFields() {
        // 每次先校验时光归属；首屏无边界，后续页保留三个排序字段。
        when(albums.selectById(id)).thenReturn(album(id));
        when(reader.selectFirstTimelinePage(id, user, 3)).thenReturn(List.of());
        service.browseAlbumMoments(id, user, new PageRequest<>(null, 2));
        verify(reader).selectFirstTimelinePage(id, user, 3);
        verify(reader, never()).selectNextTimelinePage(any(), any(), any(), any(), any(), anyInt());

        Instant memoryTime = Instant.parse("2026-01-01T08:00:00+08:00");
        Instant createdAt = Instant.parse("2026-01-02T08:00:00+08:00");
        LocalDateTime memoryUtc = LocalDateTime.parse("2026-01-01T00:00:00");
        LocalDateTime createdUtc = LocalDateTime.parse("2026-01-02T00:00:00");
        when(reader.selectNextTimelinePage(id, user, memoryUtc, createdUtc, id, 3)).thenReturn(List.of());
        service.browseAlbumMoments(id, user,
                new PageRequest<>(new AlbumMomentCursor(memoryTime, createdAt, id), 2));
        verify(reader).selectNextTimelinePage(id, user, memoryUtc, createdUtc, id, 3);
        verify(reader, times(1)).selectFirstTimelinePage(any(), any(), anyInt());
        verifyNoInteractions(details, media);
    }

    private Album album(UUID albumId) {
        // 构造具有完整归属和时间信息的时光，供分页业务边界验证。
        Album album = new Album();
        album.setId(albumId);
        album.setOwnerUserId(user);
        album.setName("测试时光");
        album.setCreatedAt(LocalDateTime.parse("2026-01-01T00:00:00"));
        album.setUpdatedAt(album.getCreatedAt());
        return album;
    }
}
