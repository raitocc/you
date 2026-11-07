package com.you.youbackend.moment;

import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.entity.MomentImage;
import com.you.youbackend.moment.mapper.MomentImageMapper;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.mapper.MomentTagMapper;
import com.you.youbackend.moment.service.MomentService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 详情查询验证：访问范围、失效图片过滤和图片顺序。 */
class MomentDetailTest {
    private final MomentMapper moments = mock(MomentMapper.class);
    private final MomentImageMapper images = mock(MomentImageMapper.class);
    private final MomentTagMapper tags = mock(MomentTagMapper.class);
    private final MediaService media = mock(MediaService.class);
    private final MomentService service = new MomentService(moments, images, tags, media);
    private final UUID userId = UUID.randomUUID();
    private final UUID momentId = UUID.randomUUID();

    @Test
    void skipsUnavailableImagesAndKeepsRemainingOrder() {
        when(moments.selectById(momentId)).thenReturn(moment(userId));
        UUID first = UUID.randomUUID(), missing = UUID.randomUUID(), last = UUID.randomUUID();
        when(images.selectInDisplayOrder(momentId)).thenReturn(List.of(image(first), image(missing), image(last)));
        when(media.findOwnedImageKeys(userId, List.of(first, missing, last)))
                .thenReturn(Map.of(first, "first-key", last, "last-key"));
        when(media.createReadUrl("first-key")).thenReturn("first-url");
        when(media.createReadUrl("last-key")).thenReturn("last-url");
        when(tags.selectNames(momentId)).thenReturn(List.of("旅行"));

        var response = service.getMoment(momentId, userId);

        assertEquals(List.of(first, last), response.images().stream().map(item -> item.fileId()).toList());
        assertEquals(List.of("first-url", "last-url"), response.images().stream().map(item -> item.url()).toList());
        assertEquals("正文", response.body());
        assertEquals(List.of("旅行"), response.tags());
        verify(media, times(2)).createReadUrl(anyString());
    }

    @Test
    void returnsEmptyImagesWithoutQueryingMediaWhenNoAssociations() {
        when(moments.selectById(momentId)).thenReturn(moment(userId));
        when(images.selectInDisplayOrder(momentId)).thenReturn(List.of());
        when(tags.selectNames(momentId)).thenReturn(List.of());
        assertTrue(service.getMoment(momentId, userId).images().isEmpty());
        verifyNoInteractions(media);
    }

    @Test
    void rejectsMissingOrForeignMomentBeforeLoadingAssociations() {
        assertEquals("MOMENT_NOT_FOUND", assertThrows(BusinessException.class,
                () -> service.getMoment(momentId, userId)).getCode());
        when(moments.selectById(momentId)).thenReturn(moment(UUID.randomUUID()));
        assertEquals("FORBIDDEN", assertThrows(BusinessException.class,
                () -> service.getMoment(momentId, userId)).getCode());
        verifyNoInteractions(images, tags, media);
    }

    private Moment moment(UUID authorId) {
        Moment moment = new Moment();
        moment.setId(momentId);
        moment.setAuthorUserId(authorId);
        moment.setBody("正文");
        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0);
        moment.setMemoryTime(time);
        moment.setCreatedAt(time);
        moment.setUpdatedAt(time);
        return moment;
    }

    private MomentImage image(UUID fileId) {
        MomentImage image = new MomentImage();
        image.setFileId(fileId);
        return image;
    }
}
