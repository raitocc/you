package com.you.youbackend.album;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.album.entity.Album;
import com.you.youbackend.album.entity.AlbumMoment;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.album.model.AlbumOrganizeMode;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.request.PageRequest;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.entity.MomentImage;
import com.you.youbackend.moment.entity.MomentTag;
import com.you.youbackend.moment.mapper.MomentMapper;
import com.you.youbackend.moment.mapper.MomentImageMapper;
import com.you.youbackend.moment.mapper.MomentTagMapper;
import com.you.youbackend.moment.model.MomentMood;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** MySQL 集成验证：分页排序、删除过滤、分组数量、完整详情批量组装。测试事务自动回滚。 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Transactional
class AlbumPaginationDatabaseTest {
    @Autowired private AlbumService service;
    @Autowired private AlbumMapper albums;
    @Autowired private AlbumMomentMapper associations;
    @Autowired private MomentMapper moments;
    @Autowired private MomentImageMapper images;
    @Autowired private MomentTagMapper tags;
    @MockitoBean private MediaService media;
    private final UUID user = UUID.randomUUID();
    private final LocalDateTime time = LocalDateTime.parse("2026-01-01T00:00:00.123");

    @Test
    void albumPagesUseTimeAndIdAndKeepEmptyAlbums() {
        // 同一创建时间的三条本人时光，另插入更早记录、他人时光和已删除时光。
        List<Album> sameTime = new ArrayList<>(List.of(album(user, time), album(user, time), album(user, time)));
        sameTime.sort(Comparator.comparing((Album row) -> row.getId().toString()).reversed());
        Album earlier = album(user, time.minusDays(1));
        album(UUID.randomUUID(), time.plusDays(1));
        Album deleted = album(user, time.plusDays(2));
        albums.deleteById(deleted.getId());
        var first = service.browseAlbums(user, new PageRequest<>(null, 2));
        assertEquals(sameTime.subList(0, 2).stream().map(Album::getId).toList(), first.items().stream().map(item -> item.id()).toList());
        assertTrue(first.hasMore());
        assertEquals(sameTime.get(1).getId(), first.nextCursor().id());
        assertTrue(first.items().stream().allMatch(item -> item.momentCount() == 0 && item.coverUrl() == null));

        // 游标所指记录删除后，下一页仍按原排序位置继续读取。
        albums.deleteById(sameTime.get(1).getId());
        var second = service.browseAlbums(user, new PageRequest<>(first.nextCursor(), 2));
        assertEquals(List.of(sameTime.get(2).getId(), earlier.getId()), second.items().stream().map(item -> item.id()).toList());
        assertFalse(second.hasMore());
        assertNull(second.nextCursor());
        assertTrue(service.browseAlbums(UUID.randomUUID(), new PageRequest<>(null, 20)).items().isEmpty());
    }

    @Test
    void batchCountsAndCoversExcludeInvalidResources() {
        Album first = album(user, time), second = album(user, time), empty = album(user, time);
        UUID validCover = UUID.randomUUID(), invalidCover = UUID.randomUUID();
        first.setCoverFileId(validCover);
        second.setCoverFileId(invalidCover);
        albums.updateById(first);
        albums.updateById(second);
        Moment active = moment(user, time, time), removed = moment(user, time, time);
        Moment deleted = moment(user, time, time), foreign = moment(UUID.randomUUID(), time, time);
        link(first, List.of(active, removed, deleted, foreign));
        link(second, List.of(active));
        moments.deleteById(deleted.getId());
        associations.delete(Wrappers.<AlbumMoment>lambdaQuery().eq(AlbumMoment::getAlbumId, first.getId())
                .eq(AlbumMoment::getMomentId, removed.getId()));
        when(media.findOwnedImageKeys(eq(user), anyCollection())).thenReturn(Map.of(validCover, "valid-key"));
        when(media.createReadUrl("valid-key")).thenReturn("signed-cover");
        var result = service.browseAlbums(user, new PageRequest<>(null, 20));
        var byId = result.items().stream().collect(java.util.stream.Collectors.toMap(item -> item.id(), item -> item));
        assertEquals(1, byId.get(first.getId()).momentCount());
        assertEquals(1, byId.get(second.getId()).momentCount());
        assertEquals(0, byId.get(empty.getId()).momentCount());
        assertEquals("signed-cover", byId.get(first.getId()).coverUrl());
        assertNull(byId.get(second.getId()).coverUrl());
        verify(media, times(1)).findOwnedImageKeys(eq(user), anyCollection());
        verify(media, times(1)).createReadUrl("valid-key");
    }

    @Test
    void timelinePagesUseAllThreeAscendingFieldsAndFilterInvalidRows() {
        Album album = album(user, time);
        Moment earliest = moment(user, time.minusDays(1), time.plusDays(5));
        Moment publishedEarlier = moment(user, time, time.minusDays(1));
        List<Moment> ties = new ArrayList<>(List.of(moment(user, time, time), moment(user, time, time), moment(user, time, time)));
        ties.sort(Comparator.comparing(row -> row.getId().toString()));
        List<Moment> expected = new ArrayList<>(List.of(earliest, publishedEarlier));
        expected.addAll(ties);
        Moment deleted = moment(user, time.minusDays(2), time), removed = moment(user, time, time);
        Moment foreign = moment(UUID.randomUUID(), time, time);
        List<Moment> linked = new ArrayList<>(expected);
        linked.addAll(List.of(deleted, removed, foreign));
        link(album, linked);
        moments.deleteById(deleted.getId());
        associations.delete(Wrappers.<AlbumMoment>lambdaQuery().eq(AlbumMoment::getAlbumId, album.getId())
                .eq(AlbumMoment::getMomentId, removed.getId()));
        // 页大小为 3，让第一页停在时间完全相同的记录之间，验证 ID 边界。
        var first = service.browseAlbumMoments(album.getId(), user, new PageRequest<>(null, 3));
        var second = service.browseAlbumMoments(album.getId(), user, new PageRequest<>(first.nextCursor(), 3));
        List<UUID> actual = new ArrayList<>(first.items().stream().map(item -> item.id()).toList());
        actual.addAll(second.items().stream().map(item -> item.id()).toList());
        assertEquals(expected.stream().map(Moment::getId).toList(), actual);
        assertTrue(first.hasMore());
        assertFalse(second.hasMore());
        assertNull(second.nextCursor());
        assertEquals(5, service.getAlbum(album.getId(), user).momentCount());
    }

    @Test
    void timelineReturnsCurrentDetailShapeWithOrderedValidImagesAndTags() {
        Album album = album(user, time);
        Moment moment = moment(user, time, time);
        moment.setMood(MomentMood.ANXIOUS);
        moment.setLocationName("杭州");
        moments.updateById(moment);
        link(album, List.of(moment));
        UUID firstFile = UUID.randomUUID(), secondFile = UUID.randomUUID(), invalidFile = UUID.randomUUID();
        image(moment, secondFile, 3);
        image(moment, invalidFile, 2);
        image(moment, firstFile, 1);
        tag(moment, "旅行", time);
        tag(moment, "周末", time.plusSeconds(1));
        when(media.findOwnedImageKeys(eq(user), anyCollection())).thenReturn(Map.of(firstFile, "first", secondFile, "second"));
        when(media.createReadUrl("first")).thenReturn("first-url");
        when(media.createReadUrl("second")).thenReturn("second-url");
        var result = service.browseAlbumMoments(album.getId(), user, new PageRequest<>(null, 20));
        var item = result.items().get(0);
        assertEquals("测试正文", item.body());
        assertEquals(MomentMood.ANXIOUS, item.mood());
        assertEquals("杭州", item.location().name());
        assertEquals(List.of("旅行", "周末"), item.tags());
        assertEquals(List.of(firstFile, secondFile), item.images().stream().map(image -> image.fileId()).toList());
        assertEquals(List.of("first-url", "second-url"), item.images().stream().map(image -> image.url()).toList());
        verify(media, times(1)).findOwnedImageKeys(eq(user), anyCollection());
    }

    @Test
    void emptyAndUnauthorizedAlbumsRespectPermissions() {
        Album owned = album(user, time), foreign = album(UUID.randomUUID(), time);
        var result = service.browseAlbumMoments(owned.getId(), user, new PageRequest<>(null, 20));
        assertTrue(result.items().isEmpty());
        assertNull(result.nextCursor());
        assertFalse(result.hasMore());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class, () ->
                service.browseAlbumMoments(foreign.getId(), user, new PageRequest<>(null, 20))).getStatus());
        albums.deleteById(owned.getId());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class, () ->
                service.browseAlbumMoments(owned.getId(), user, new PageRequest<>(null, 20))).getStatus());
        verifyNoInteractions(media);
    }

    private Album album(UUID owner, LocalDateTime createdAt) {
        // 创建具有指定所有者和创建时间的时光，以验证查询范围及排序。
        Album album = new Album();
        album.setId(UuidCreator.getTimeOrderedEpoch());
        album.setOwnerUserId(owner);
        album.setName("测试时光");
        album.setOrganizeMode(AlbumOrganizeMode.manual);
        album.setCreatedAt(createdAt);
        album.setUpdatedAt(createdAt);
        albums.insert(album);
        return album;
    }

    private Moment moment(UUID owner, LocalDateTime memoryTime, LocalDateTime createdAt) {
        // 独立设置发生时间和发布时间，以验证时光线的三字段排序。
        Moment moment = new Moment();
        moment.setId(UuidCreator.getTimeOrderedEpoch());
        moment.setAuthorUserId(owner);
        moment.setBody("测试正文");
        moment.setMemoryTime(memoryTime);
        moment.setCreatedAt(createdAt);
        moment.setUpdatedAt(createdAt);
        moments.insert(moment);
        return moment;
    }

    private void link(Album album, List<Moment> page) {
        // 直接建立测试关联，允许构造他人瞬间等异常历史数据来验证读取过滤。
        associations.insertAssociations(page.stream().map(moment -> {
            AlbumMoment row = new AlbumMoment();
            row.setId(UuidCreator.getTimeOrderedEpoch());
            row.setAlbumId(album.getId());
            row.setMomentId(moment.getId());
            row.setCreatedAt(time);
            row.setUpdatedAt(time);
            return row;
        }).toList());
    }

    private void image(Moment moment, UUID file, int order) {
        // 建立指定展示顺序的图片关联，文件有效性由模拟媒体服务控制。
        MomentImage image = new MomentImage();
        image.setId(UuidCreator.getTimeOrderedEpoch());
        image.setMomentId(moment.getId());
        image.setFileId(file);
        image.setSortOrder(order);
        image.setCreatedAt(time);
        image.setUpdatedAt(time);
        images.insert(image);
    }

    private void tag(Moment moment, String name, LocalDateTime createdAt) {
        // 指定标签创建时间，验证批量读取与原详情保持一致顺序。
        MomentTag tag = new MomentTag();
        tag.setId(UuidCreator.getTimeOrderedEpoch());
        tag.setMomentId(moment.getId());
        tag.setTagName(name);
        tag.setCreatedAt(createdAt);
        tag.setUpdatedAt(createdAt);
        tags.insert(tag);
    }
}
