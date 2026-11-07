package com.you.youbackend.moment;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.entity.MomentImage;
import com.you.youbackend.moment.entity.MomentTag;
import com.you.youbackend.moment.mapper.MomentImageMapper;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.mapper.MomentTagMapper;
import com.you.youbackend.moment.model.MomentLocation;
import com.you.youbackend.moment.model.MomentMood;
import com.you.youbackend.moment.request.CreateMomentRequest;
import com.you.youbackend.moment.service.MomentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 发布业务单元测试：验证图片先校验、关联顺序、字段整理和失败时停止后续写入。 */
class MomentCreationTest {
    private final MomentMapper moments = mock(MomentMapper.class);
    private final MomentImageMapper images = mock(MomentImageMapper.class);
    private final MomentTagMapper tags = mock(MomentTagMapper.class);
    private final MediaService media = mock(MediaService.class);
    private final MomentService service = new MomentService(moments, images, tags, media);
    private final UUID userId = UUID.randomUUID();
    private final UUID momentId = UuidCreator.getTimeOrderedEpoch();
    private final Instant time = Instant.parse("2026-01-01T00:00:00.123456Z");

    @Test
    void savesNormalizedMomentAndOrderedAssociationsBeforeSigning() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        List<UUID> ids = List.of(first, second);
        when(media.findOwnedImageKeys(userId, ids)).thenReturn(Map.of(first, "first-key", second, "second-key"));
        when(media.createReadUrl("first-key")).thenReturn("first-url");
        when(media.createReadUrl("second-key")).thenReturn("second-url");
        when(tags.insertIfAbsent(any())).thenAnswer(call -> !((MomentTag) call.getArgument(0)).getTagName().equals("cat"));
        var request = new CreateMomentRequest(momentId, "   ", "正文", ids,
                List.of(" 旅行 ", "旅行", "CAT", "cat", " "), MomentMood.HAPPY, time,
                new MomentLocation(new BigDecimal("31.123456789"), new BigDecimal("121.987654321"), "上海", null));

        var response = service.createMoment(request, userId);

        var saved = ArgumentCaptor.forClass(Moment.class);
        verify(moments).insert(saved.capture());
        assertNull(saved.getValue().getTitle());
        assertEquals(userId, saved.getValue().getAuthorUserId());
        assertEquals(LocalDateTime.parse("2026-01-01T00:00:00.123"), saved.getValue().getMemoryTime());
        assertEquals(saved.getValue().getCreatedAt(), saved.getValue().getUpdatedAt());
        assertEquals(new BigDecimal("31.1234568"), saved.getValue().getLatitude());
        assertEquals(Instant.parse("2026-01-01T00:00:00.123Z"), response.memoryTime());
        assertEquals(List.of(first, second), response.images().stream().map(image -> image.fileId()).toList());
        assertEquals(List.of("first-url", "second-url"), response.images().stream().map(image -> image.url()).toList());
        assertEquals(List.of("旅行", "CAT"), response.tags());

        var associations = ArgumentCaptor.forClass(MomentImage.class);
        verify(images, times(2)).insert(associations.capture());
        assertEquals(List.of(1, 2), associations.getAllValues().stream().map(MomentImage::getSortOrder).toList());
        assertTrue(associations.getAllValues().stream().allMatch(image -> image.getId().version() == 7));
        var order = inOrder(media, moments, images, tags);
        order.verify(media).findOwnedImageKeys(userId, ids);
        order.verify(moments).insert(any(Moment.class));
        order.verify(images, times(2)).insert(any(MomentImage.class));
        order.verify(tags, times(3)).insertIfAbsent(any());
        order.verify(media).createReadUrl("first-key");
    }

    @Test
    void supportsTextOnlyPublicationWithOptionalFieldsAbsent() {
        when(media.findOwnedImageKeys(userId, List.of())).thenReturn(Map.of());
        var response = service.createMoment(request("正文", null, time, null), userId);
        assertTrue(response.images().isEmpty());
        assertTrue(response.tags().isEmpty());
        assertNull(response.location());
        verifyNoInteractions(images, tags);
        verify(media, never()).createReadUrl(anyString());
    }

    @Test
    void rejectsUnavailableImageBeforeWritingAnything() {
        List<UUID> ids = List.of(UUID.randomUUID());
        when(media.findOwnedImageKeys(userId, ids)).thenReturn(Map.of());
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMoment(request("正文", ids, time, null), userId));
        assertEquals("FILE_NOT_USABLE", error.getCode());
        verifyNoInteractions(moments, images, tags);
        verify(media, never()).createReadUrl(anyString());
    }

    @Test
    void rejectsEmptyContentAndDuplicateImagesBeforeQueryingMedia() {
        UUID fileId = UUID.randomUUID();
        for (CreateMomentRequest request : Arrays.asList(request(" ", null, time, null),
                request("正文", List.of(fileId, fileId), time, null))) {
            assertEquals("INVALID_ARGUMENT", assertThrows(BusinessException.class,
                    () -> service.createMoment(request, userId)).getCode());
        }
        verifyNoInteractions(moments, images, tags, media);
    }

    @Test
    void duplicateMomentIdStopsAssociationWritesAndSigning() {
        when(media.findOwnedImageKeys(userId, List.of())).thenReturn(Map.of());
        when(moments.insert(any(Moment.class))).thenThrow(new DuplicateKeyException("duplicate"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createMoment(request("正文", null, time, null), userId));
        assertEquals("MOMENT_ID_CONFLICT", error.getCode());
        verifyNoInteractions(images, tags);
        verify(media, never()).createReadUrl(anyString());
    }

    @Test
    void rejectsInvalidLocationAndDatabaseTimeBeforeWriting() {
        MomentLocation incomplete = new MomentLocation(BigDecimal.ONE, null, null, null);
        for (CreateMomentRequest request : Arrays.asList(request("正文", null, time, incomplete),
                request("正文", null, Instant.parse("0999-01-01T00:00:00Z"), null))) {
            assertEquals("INVALID_ARGUMENT", assertThrows(BusinessException.class,
                    () -> service.createMoment(request, userId)).getCode());
        }
        verifyNoInteractions(moments, images, tags, media);
    }

    private CreateMomentRequest request(String body, List<UUID> ids, Instant memoryTime, MomentLocation location) {
        return new CreateMomentRequest(momentId, null, body, ids, null, null, memoryTime, location);
    }
}
