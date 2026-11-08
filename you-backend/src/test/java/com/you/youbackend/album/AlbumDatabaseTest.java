package com.you.youbackend.album;

import com.github.f4b6a3.uuid.UuidCreator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.you.youbackend.album.entity.AlbumMoment;
import com.you.youbackend.album.mapper.AlbumMapper;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.album.model.AlbumOrganizeMode;
import com.you.youbackend.album.request.CreateAlbumRequest;
import com.you.youbackend.album.request.UpdateAlbumRequest;
import com.you.youbackend.album.service.AlbumService;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.service.MediaService;
import com.you.youbackend.moment.entity.Moment;
import com.you.youbackend.moment.mapper.MomentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用项目配置的 MySQL 验证 UUID 映射、SQL 过滤与 Spring 代理事务；媒体签名使用模拟服务。 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class AlbumDatabaseTest {
    @Autowired private AlbumService service;
    @Autowired private AlbumMapper albums;
    @MockitoSpyBean private AlbumMomentMapper associations;
    @Autowired private MomentMapper moments;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private MediaService media;

    @Test
    @Transactional
    void persistsDeduplicatedAssociationsAndCountsOnlyValidOwnedMoments() {
        UUID user = UUID.randomUUID();
        Moment first = saveMoment(user), second = saveMoment(user), foreign = saveMoment(UUID.randomUUID());
        var result = service.createAlbum(new CreateAlbumRequest("  旅行  ", "😀".repeat(5000), null,
                List.of(first.getId(), second.getId(), first.getId())), user);
        assertEquals(2, result.momentCount());
        assertEquals(AlbumOrganizeMode.manual, albums.selectById(result.id()).getOrganizeMode());
        assertEquals("😀".repeat(5000), service.getAlbum(result.id(), user).description());
        assertEquals("旅行", result.name());
        moments.deleteById(first.getId());
        assertEquals(1, service.getAlbum(result.id(), user).momentCount());

        AlbumMoment other = new AlbumMoment();
        other.setId(UuidCreator.getTimeOrderedEpoch());
        other.setAlbumId(result.id());
        other.setMomentId(foreign.getId());
        other.setCreatedAt(second.getCreatedAt());
        other.setUpdatedAt(second.getUpdatedAt());
        associations.insertAssociations(List.of(other));
        assertEquals(1, service.getAlbum(result.id(), user).momentCount());
        associations.delete(Wrappers.<AlbumMoment>lambdaQuery()
                .eq(AlbumMoment::getAlbumId, result.id()).eq(AlbumMoment::getMomentId, second.getId()));
        assertEquals(0, service.getAlbum(result.id(), user).momentCount());
        albums.deleteById(result.id());
        assertEquals("ALBUM_NOT_FOUND", assertThrows(BusinessException.class,
                () -> service.getAlbum(result.id(), user)).getCode());
    }

    @Test
    @Transactional
    void rejectsDeletedAndForeignMomentsUsingRealMapperFiltering() {
        UUID user = UUID.randomUUID();
        Moment deleted = saveMoment(user), foreign = saveMoment(UUID.randomUUID());
        moments.deleteById(deleted.getId());
        for (UUID id : List.of(deleted.getId(), foreign.getId(), UUID.randomUUID())) {
            assertThrows(BusinessException.class, () -> service.createAlbum(
                    new CreateAlbumRequest("旅行", null, null, List.of(id)), user));
        }
    }

    @Test
    void rollsBackMainRecordAndAssociationsWhenSigningFailsAfterWrites() {
        UUID user = UUID.randomUUID(), cover = UUID.randomUUID();
        Moment moment = saveMoment(user);
        when(media.findOwnedImageKeys(user, List.of(cover))).thenReturn(Map.of(cover, "key"));
        when(media.createReadUrl("key")).thenThrow(new IllegalStateException("模拟签名失败"));
        try {
            assertThrows(IllegalStateException.class, () -> service.createAlbum(new CreateAlbumRequest(
                    "事务回滚测试", null, cover, List.of(moment.getId())), user));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM album WHERE owner_user_id = ?",
                    Long.class, bytes(user)));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM album_moment WHERE moment_id = ?",
                    Long.class, bytes(moment.getId())));
        } finally {
            // 仅清理本测试随机 ID 对应数据，即便断言失败也不留下测试记录。
            jdbc.update("DELETE FROM album_moment WHERE moment_id = ?", bytes(moment.getId()));
            jdbc.update("DELETE FROM album WHERE owner_user_id = ?", bytes(user));
            jdbc.update("DELETE FROM moment WHERE id = ?", bytes(moment.getId()));
        }
    }

    @Test
    @Transactional
    void clearsFieldsAndDeletesOnlyThisAlbumsAssociations() {
        UUID user = UUID.randomUUID();
        Moment moment = saveMoment(user);
        var first = service.createAlbum(new CreateAlbumRequest("第一个", "介绍", null, List.of(moment.getId())), user);
        var second = service.createAlbum(new CreateAlbumRequest("第二个", null, null, List.of(moment.getId())), user);
        // 直接设置已有封面，随后通过真实 Mapper 验证 null 确实落库。
        jdbc.update("UPDATE album SET cover_file_id = ? WHERE id = ?", bytes(UUID.randomUUID()), bytes(first.id()));
        service.updateAlbum(first.id(), new UpdateAlbumRequest("新名称", null, null), user);
        var saved = albums.selectById(first.id());
        assertNull(saved.getDescription());
        assertNull(saved.getCoverFileId());
        assertEquals(first.createdAt(), saved.getCreatedAt().toInstant(ZoneOffset.UTC));
        service.deleteAlbum(first.id(), user);
        var deleted = albums.selectIncludingDeleted(first.id());
        assertNotNull(deleted.getDeletedAt());
        service.deleteAlbum(first.id(), user);
        assertEquals(deleted.getDeletedAt(), albums.selectIncludingDeleted(first.id()).getDeletedAt());
        assertThrows(BusinessException.class, () -> service.getAlbum(first.id(), user));
        assertEquals(0, associations.countValidMoments(first.id(), user));
        assertEquals(1, service.getAlbum(second.id(), user).momentCount());
        assertNotNull(moments.selectById(moment.getId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM album_moment WHERE album_id = ? AND deleted_at = ? AND updated_at = ?",
                Integer.class, bytes(first.id()), deleted.getDeletedAt(), deleted.getDeletedAt()));
    }

    @Test
    void rollsBackUpdateWhenResponseSigningFails() {
        UUID user = UUID.randomUUID(), cover = UUID.randomUUID();
        var album = service.createAlbum(new CreateAlbumRequest("原名称", "原介绍", null, List.of()), user);
        when(media.findOwnedImageKeys(user, List.of(cover))).thenReturn(Map.of(cover, "key"));
        when(media.createReadUrl("key")).thenThrow(new IllegalStateException("模拟签名失败"));
        try {
            assertThrows(IllegalStateException.class, () -> service.updateAlbum(album.id(),
                    new UpdateAlbumRequest("新名称", null, cover), user));
            var saved = albums.selectById(album.id());
            assertEquals("原名称", saved.getName());
            assertEquals("原介绍", saved.getDescription());
            assertNull(saved.getCoverFileId());
            assertEquals(album.updatedAt(), saved.getUpdatedAt().toInstant(ZoneOffset.UTC));
        } finally {
            // 仅清理本测试创建的时光。
            jdbc.update("DELETE FROM album WHERE id = ?", bytes(album.id()));
        }
    }

    @Test
    void rollsBackDeletionWhenAssociationRemovalFails() {
        UUID user = UUID.randomUUID();
        Moment moment = saveMoment(user);
        var album = service.createAlbum(new CreateAlbumRequest("删除回滚", null, null,
                List.of(moment.getId())), user);
        doThrow(new IllegalStateException("模拟关联移除失败")).when(associations)
                .removeAssociations(eq(album.id()), any(LocalDateTime.class));
        try {
            assertThrows(IllegalStateException.class, () -> service.deleteAlbum(album.id(), user));
            assertNull(albums.selectIncludingDeleted(album.id()).getDeletedAt());
            assertEquals(album.updatedAt(), albums.selectById(album.id()).getUpdatedAt().toInstant(ZoneOffset.UTC));
            assertEquals(1, service.getAlbum(album.id(), user).momentCount());
            assertNotNull(moments.selectById(moment.getId()));
        } finally {
            // 清理当前测试创建的关联、时光和瞬间，不影响其他记录。
            jdbc.update("DELETE FROM album_moment WHERE album_id = ?", bytes(album.id()));
            jdbc.update("DELETE FROM album WHERE id = ?", bytes(album.id()));
            jdbc.update("DELETE FROM moment WHERE id = ?", bytes(moment.getId()));
        }
    }

    private Moment saveMoment(UUID user) {
        Moment moment = new Moment();
        moment.setId(UuidCreator.getTimeOrderedEpoch());
        moment.setAuthorUserId(user);
        moment.setBody("测试瞬间");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        moment.setMemoryTime(now);
        moment.setCreatedAt(now);
        moment.setUpdatedAt(now);
        moments.insert(moment);
        return moment;
    }

    private byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
