package com.you.youbackend.moment;

import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentImageMapper;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.mapper.MomentTagMapper;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 删除业务验证：统一审计时间、重复删除和归属检查。 */
class MomentDeletionTest {
    private final MomentMapper moments = mock(MomentMapper.class);
    private final MomentImageMapper images = mock(MomentImageMapper.class);
    private final MomentTagMapper tags = mock(MomentTagMapper.class);
    private final MediaService media = mock(MediaService.class);
    private final MomentService service = new MomentService(moments, images, tags, media, mock(GatheringService.class), mock(UserService.class));
    private final UUID id = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void deletesMomentAndAssociationsWithSameTimestampAndKeepsFiles() {
        when(moments.selectIncludingDeleted(id)).thenReturn(moment(userId));
        service.deleteMoment(id, userId);
        var time = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(moments).softDelete(eq(id), time.capture());
        verify(images).removeAssociations(id, time.getValue());
        verify(tags).removeAssociations(id, time.getValue());
        assertEquals(0, time.getValue().getNano() % 1_000_000);
        verifyNoInteractions(media);
    }

    @Test
    void repeatedDeletionDoesNotWriteAgain() {
        Moment deleted = moment(userId);
        deleted.setDeletedAt(LocalDateTime.now());
        when(moments.selectIncludingDeleted(id)).thenReturn(deleted);
        service.deleteMoment(id, userId);
        verify(moments, never()).softDelete(any(), any());
        verifyNoInteractions(images, tags, media);
    }

    @Test
    void rejectsMissingAndForeignMomentsBeforeWriting() {
        assertEquals("MOMENT_NOT_FOUND", assertThrows(BusinessException.class,
                () -> service.deleteMoment(id, userId)).getCode());
        Moment foreign = moment(UUID.randomUUID());
        foreign.setDeletedAt(LocalDateTime.now());
        when(moments.selectIncludingDeleted(id)).thenReturn(foreign);
        assertEquals("FORBIDDEN", assertThrows(BusinessException.class,
                () -> service.deleteMoment(id, userId)).getCode());
        verify(moments, never()).softDelete(any(), any());
        verifyNoInteractions(images, tags, media);
    }

    private Moment moment(UUID authorId) {
        Moment moment = new Moment();
        moment.setId(id);
        moment.setAuthorUserId(authorId);
        return moment;
    }
}
