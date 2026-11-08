package com.you.youbackend.album;

import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.entity.AlbumMoment;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.album.model.AlbumOrganizeMode;
import com.you.youbackend.album.request.CreateAlbumRequest;
import com.you.youbackend.album.request.AddAlbumMomentsRequest;
import com.you.youbackend.album.request.RemoveAlbumMomentsRequest;
import com.you.youbackend.album.request.UpdateAlbumRequest;
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

    @Test
    void updatesOnlyDisplayInformationAndClearsNullableFields() {
        UUID id = UUID.randomUUID();
        Album album = album(id);
        album.setDescription("旧介绍");
        album.setCoverFileId(UUID.randomUUID());
        when(albums.selectById(id)).thenReturn(album);
        when(albums.updateContent(any())).thenReturn(1);
        when(associations.countValidMoments(id, userId)).thenReturn(3L);
        var result = service.updateAlbum(id, new UpdateAlbumRequest("  新名称  ", null, null), userId);
        assertEquals("新名称", result.name());
        assertNull(result.description());
        assertNull(result.coverFileId());
        assertEquals(3, result.momentCount());
        assertEquals(album.getCreatedAt().toInstant(java.time.ZoneOffset.UTC), result.createdAt());
        verifyNoInteractions(moments, media);
        verify(associations, never()).removeAssociations(any(), any());
    }

    @Test
    void rejectsUpdateBeforeWritingWhenOwnerOrCoverIsInvalid() {
        UUID id = UUID.randomUUID(), cover = UUID.randomUUID();
        var request = new UpdateAlbumRequest("名称", null, cover);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.updateAlbum(id, request, userId)).getStatus());
        Album album = album(id);
        album.setOwnerUserId(UUID.randomUUID());
        when(albums.selectById(id)).thenReturn(album);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.updateAlbum(id, request, userId)).getStatus());
        album.setOwnerUserId(userId);
        when(media.findOwnedImageKeys(userId, List.of(cover))).thenReturn(Map.of());
        assertEquals("FILE_NOT_USABLE", assertThrows(BusinessException.class,
                () -> service.updateAlbum(id, request, userId)).getCode());
        verify(albums, never()).updateContent(any());
    }

    @Test
    void deletesWithOneTimestampAndChecksOwnershipBeforeIdempotency() {
        UUID id = UUID.randomUUID();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.deleteAlbum(id, userId)).getStatus());
        Album album = album(id);
        when(albums.selectIncludingDeleted(id)).thenReturn(album);
        service.deleteAlbum(id, userId);
        var time = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(albums).softDelete(eq(id), time.capture());
        verify(associations).removeAssociations(id, time.getValue());
        clearInvocations(albums, associations);
        album.setDeletedAt(time.getValue());
        service.deleteAlbum(id, userId);
        verify(albums, never()).softDelete(any(), any());
        verifyNoInteractions(associations, moments, media);
        album.setOwnerUserId(UUID.randomUUID());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.deleteAlbum(id, userId)).getStatus());
    }

    @Test
    void addsNewRestoresHistoricalAndPreservesActiveAssociations() {
        UUID id = UUID.randomUUID(), active = UUID.randomUUID(), restored = UUID.randomUUID(), added = UUID.randomUUID();
        Album album = album(id);
        when(albums.selectById(id)).thenReturn(album);
        when(albums.touch(eq(id), any())).thenReturn(1);
        var ids = List.of(active, restored, added);
        when(moments.selectOwnedIds(userId, ids)).thenReturn(ids);
        AlbumMoment activeRow = new AlbumMoment(), removedRow = new AlbumMoment();
        activeRow.setMomentId(active);
        removedRow.setMomentId(restored);
        removedRow.setDeletedAt(album.getCreatedAt());
        when(associations.selectIncludingDeleted(id, ids)).thenReturn(List.of(activeRow, removedRow));
        when(associations.countValidMoments(id, userId)).thenReturn(3L);
        var result = service.addAlbumMoments(id, new AddAlbumMomentsRequest(List.of(active, restored, added, added)), userId);
        assertEquals(3, result.momentCount());
        var rows = ArgumentCaptor.forClass(List.class);
        verify(associations).insertAssociations(rows.capture());
        AlbumMoment inserted = (AlbumMoment) rows.getValue().get(0);
        assertEquals(1, rows.getValue().size());
        assertEquals(added, inserted.getMomentId());
        verify(associations).restoreAssociations(id, List.of(restored), inserted.getCreatedAt());
        verify(albums).touch(id, inserted.getCreatedAt());
        verifyNoInteractions(media);
    }

    @Test
    void rejectsWholeAdditionBeforeWritingIfAnyMomentIsInvalid() {
        UUID id = UUID.randomUUID(), first = UUID.randomUUID(), invalid = UUID.randomUUID();
        when(albums.selectById(id)).thenReturn(album(id));
        when(moments.selectOwnedIds(userId, List.of(first, invalid))).thenReturn(List.of(first));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(BusinessException.class, () -> service.addAlbumMoments(
                id, new AddAlbumMomentsRequest(List.of(first, invalid)), userId)).getStatus());
        verifyNoInteractions(associations, media);
        verify(albums, never()).touch(any(), any());
    }

    @Test
    void removalDoesNotValidateOriginalMomentsAndDeduplicatesIds() {
        UUID id = UUID.randomUUID(), unknown = UUID.randomUUID();
        when(albums.selectById(id)).thenReturn(album(id));
        when(albums.touch(eq(id), any())).thenReturn(1);
        var response = service.removeAlbumMoments(id, new RemoveAlbumMomentsRequest(List.of(unknown, unknown)), userId);
        assertEquals(0, response.momentCount());
        verify(associations).removeSelectedAssociations(eq(id), eq(List.of(unknown)), any());
        verifyNoInteractions(moments, media);
    }

    @Test
    void bothAssociationOperationsRejectMissingOrForeignAlbumsBeforeResourceAccess() {
        UUID id = UUID.randomUUID(), moment = UUID.randomUUID();
        var add = new AddAlbumMomentsRequest(List.of(moment));
        var remove = new RemoveAlbumMomentsRequest(List.of(moment));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.addAlbumMoments(id, add, userId)).getStatus());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.removeAlbumMoments(id, remove, userId)).getStatus());
        Album foreign = album(id);
        foreign.setOwnerUserId(UUID.randomUUID());
        when(albums.selectById(id)).thenReturn(foreign);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.addAlbumMoments(id, add, userId)).getStatus());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.removeAlbumMoments(id, remove, userId)).getStatus());
        verifyNoInteractions(moments, associations, media);
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
