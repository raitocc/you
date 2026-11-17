package com.you.youbackend.moment;

import com.you.youbackend.gathering.service.GatheringService;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.*;
import com.you.youbackend.moment.mapper.*;
import com.you.youbackend.moment.request.UpdateMomentRequest;
import com.you.youbackend.moment.service.MomentService;
import com.you.youbackend.user.service.UserService;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 更新业务测试：关联差异同步、空数组清空及保留项不重复写入。 */
class MomentUpdateTest {
    final MomentMapper moments = mock(MomentMapper.class);
    final MomentImageMapper images = mock(MomentImageMapper.class);
    final MomentTagMapper tags = mock(MomentTagMapper.class);
    final MediaService media = mock(MediaService.class);
    final MomentService service = new MomentService(moments, images, tags, media, mock(GatheringService.class), mock(UserService.class));
    final UUID id = UUID.randomUUID(), user = UUID.randomUUID();
    final LocalDateTime oldTime = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Test
    void synchronizesNewRestoredReorderedAndRemovedImages() {
        MomentImage keep = image(1, false), restore = image(2, true), remove = image(3, false);
        UUID added = UUID.randomUUID();
        prepare(List.of(restore.getFileId(), keep.getFileId(), added));
        when(images.selectIncludingDeleted(id)).thenReturn(List.of(keep, restore, remove));
        service.updateMoment(id, request(List.of(restore.getFileId(), keep.getFileId(), added), List.of()), user);
        verify(images).restore(restore);
        assertEquals(1, restore.getSortOrder());
        assertEquals(oldTime, restore.getCreatedAt());
        verify(images).updateOrder(eq(keep.getId()), eq(2), any());
        verify(images).insert(argThat((MomentImage item) -> item.getFileId().equals(added) && item.getSortOrder() == 3));
        verify(images).softDelete(eq(remove.getId()), any());
        verify(images, never()).removeAssociations(any(), any());
    }

    @Test
    void synchronizesTagsAndSkipsDatabaseEquivalentDuplicates() {
        prepare(List.of());
        MomentTag keep = tag("旅行", false), restore = tag("CAT", true), remove = tag("旧标签", false);
        when(tags.selectActive(id)).thenReturn(List.of(keep, remove));
        when(tags.selectIncludingDeletedByName(id, "旅行")).thenReturn(keep);
        when(tags.selectIncludingDeletedByName(id, "cat")).thenReturn(restore);
        when(tags.selectIncludingDeletedByName(id, "CAT")).thenReturn(restore);
        service.updateMoment(id, request(List.of(), List.of("旅行", "cat", "CAT", "新标签")), user);
        verify(tags).restore(restore);
        assertEquals("cat", restore.getTagName());
        verify(tags).insert(argThat((MomentTag item) -> item.getTagName().equals("新标签")));
        verify(tags).softDelete(eq(remove.getId()), any());
        verify(tags, never()).softDelete(eq(keep.getId()), any());
        assertEquals(oldTime, restore.getCreatedAt());
    }

    @Test
    void emptyArraysRemoveActiveAssociationsButLeaveHistoryAndClearNullableFields() {
        prepare(List.of());
        MomentImage active = image(1, false), history = image(2, true);
        MomentTag tag = tag("移除", false);
        when(images.selectIncludingDeleted(id)).thenReturn(List.of(active, history));
        when(tags.selectActive(id)).thenReturn(List.of(tag));
        var response = service.updateMoment(id, request(List.of(), List.of()), user);
        verify(images).softDelete(eq(active.getId()), any());
        verify(images, never()).softDelete(eq(history.getId()), any());
        verify(tags).softDelete(eq(tag.getId()), any());
        verify(images, never()).restore(any());
        assertNull(response.title());
        assertNull(response.location());
        assertTrue(response.images().isEmpty());
    }

    @Test
    void unchangedImageOrderDoesNotRewriteAssociation() {
        MomentImage keep = image(1, false);
        prepare(List.of(keep.getFileId()));
        when(images.selectIncludingDeleted(id)).thenReturn(List.of(keep));
        service.updateMoment(id, request(List.of(keep.getFileId()), List.of()), user);
        verify(images, never()).updateOrder(any(), anyInt(), any());
        verify(images, never()).restore(any());
        verify(images, never()).insert(any(MomentImage.class));
        verify(images, never()).softDelete(any(), any());
    }

    private void prepare(List<UUID> files) {
        Moment moment = new Moment();
        moment.setId(id); moment.setAuthorUserId(user);
        moment.setCreatedAt(oldTime); moment.setUpdatedAt(oldTime); moment.setMemoryTime(oldTime);
        moment.setTitle("旧标题"); moment.setLocationName("旧地点");
        when(moments.selectById(id)).thenReturn(moment);
        Map<UUID, String> keys = new HashMap<>();
        files.forEach(file -> keys.put(file, file.toString()));
        when(media.findOwnedImageKeys(user, files)).thenReturn(keys);
        when(images.selectIncludingDeleted(id)).thenReturn(List.of());
        when(tags.selectActive(id)).thenReturn(List.of());
        when(tags.selectNames(id)).thenReturn(List.of());
    }
    private UpdateMomentRequest request(List<UUID> files, List<String> names) {
        return new UpdateMomentRequest(null, "正文", files, names, null, oldTime.toInstant(ZoneOffset.UTC), null);
    }
    private MomentImage image(int order, boolean deleted) {
        MomentImage image = new MomentImage();
        image.setId(UUID.randomUUID()); image.setMomentId(id); image.setFileId(UUID.randomUUID());
        image.setSortOrder(order); image.setCreatedAt(oldTime);
        if (deleted) image.setDeletedAt(oldTime);
        return image;
    }
    private MomentTag tag(String name, boolean deleted) {
        MomentTag tag = new MomentTag();
        tag.setId(UUID.randomUUID()); tag.setMomentId(id); tag.setTagName(name); tag.setCreatedAt(oldTime);
        if (deleted) tag.setDeletedAt(oldTime);
        return tag;
    }
}
