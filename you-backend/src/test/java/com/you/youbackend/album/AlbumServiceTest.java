package com.you.youbackend.album;

import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.entity.AlbumMoment;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.album.model.AlbumOrganizeMode;
import com.you.youbackend.album.request.CreateAlbumRequest;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.service.MomentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 创建和查询的业务边界测试，数据库事务另由集成测试验证。 */
class AlbumServiceTest {
    private final AlbumMapper albums = mock(AlbumMapper.class);
    private final AlbumMomentMapper associations = mock(AlbumMomentMapper.class);
    private final MomentMapper moments = mock(MomentMapper.class);
    private final MediaService media = mock(MediaService.class);
    private final AlbumService service = new AlbumService(albums, associations, moments, media, mock(MomentService.class));
    private final UUID userId = UUID.randomUUID();

    @Test
    void createsEmptyAlbumWithoutQueryingMomentsOrMedia() {
        var result = service.createAlbum(new CreateAlbumRequest("  旅行  ", null, null, List.of()), userId);
        assertEquals("旅行", result.name());
        assertEquals(7, result.id().version());
        assertEquals(AlbumOrganizeMode.manual, result.organizeMode());
        assertEquals(0, result.momentCount());
        assertEquals(result.createdAt(), result.updatedAt());
        assertNull(result.coverUrl());
        verifyNoInteractions(moments, media);
        verify(associations, never()).insertAssociations(anyList());
    }

    @Test
    void deduplicatesMomentsAndReusesValidatedCoverKey() {
        UUID momentId = UUID.randomUUID(), cover = UUID.randomUUID();
        when(moments.selectOwnedIds(userId, List.of(momentId))).thenReturn(List.of(momentId));
        when(media.findOwnedImageKeys(userId, List.of(cover))).thenReturn(Map.of(cover, "key"));
        when(media.createReadUrl("key")).thenReturn("signed-url");
        when(associations.countValidMoments(any(), eq(userId))).thenReturn(1L);
        var result = service.createAlbum(new CreateAlbumRequest("旅行", "介绍", cover,
                List.of(momentId, momentId)), userId);
        assertEquals(1, result.momentCount());
        assertEquals("signed-url", result.coverUrl());
        var capture = ArgumentCaptor.forClass(Album.class);
        verify(albums).insert(capture.capture());
        assertEquals(userId, capture.getValue().getOwnerUserId());
        assertNull(capture.getValue().getOrganizePrompt());
        var rows = ArgumentCaptor.forClass(List.class);
        verify(associations).insertAssociations(rows.capture());
        assertEquals(1, rows.getValue().size());
        AlbumMoment row = (AlbumMoment) rows.getValue().get(0);
        assertEquals(momentId, row.getMomentId());
        assertEquals(result.id(), row.getAlbumId());
        assertEquals(7, row.getId().version());
        verify(media).findOwnedImageKeys(userId, List.of(cover));
    }

    @Test
    void rejectsInvalidResourcesBeforeAnyWrite() {
        UUID id = UUID.randomUUID();
        when(media.findOwnedImageKeys(userId, List.of(id))).thenReturn(Map.of());
        assertEquals("FILE_NOT_USABLE", assertThrows(BusinessException.class, () -> service.createAlbum(
                new CreateAlbumRequest("旅行", null, id, List.of()), userId)).getCode());
        when(moments.selectOwnedIds(userId, List.of(id))).thenReturn(List.of());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class, () -> service.createAlbum(
                new CreateAlbumRequest("旅行", null, null, List.of(id)), userId)).getStatus());
        verifyNoInteractions(albums, associations);
    }

    @Test
    void checksExistenceAndOwnershipBeforeLoadingDetails() {
        UUID id = UUID.randomUUID();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.getAlbum(id, userId)).getStatus());
        Album album = album(id);
        album.setOwnerUserId(UUID.randomUUID());
        when(albums.selectById(id)).thenReturn(album);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.getAlbum(id, userId)).getStatus());
        verifyNoInteractions(associations, media);
    }

    @Test
    void preservesInvalidCoverIdButReturnsNullUrlAndCurrentCount() {
        UUID id = UUID.randomUUID(), cover = UUID.randomUUID();
        Album album = album(id);
        album.setCoverFileId(cover);
        when(albums.selectById(id)).thenReturn(album);
        when(media.findOwnedImageKeys(userId, List.of(cover))).thenReturn(Map.of());
        when(associations.countValidMoments(id, userId)).thenReturn(2L);
        var result = service.getAlbum(id, userId);
        assertEquals(cover, result.coverFileId());
        assertNull(result.coverUrl());
        assertEquals(2, result.momentCount());
        verify(media, never()).createReadUrl(anyString());
    }

    private Album album(UUID id) {
        Album album = new Album();
        album.setId(id);
        album.setOwnerUserId(userId);
        album.setCreatedAt(LocalDateTime.parse("2026-01-01T00:00:00.123"));
        album.setUpdatedAt(album.getCreatedAt());
        return album;
    }
}
