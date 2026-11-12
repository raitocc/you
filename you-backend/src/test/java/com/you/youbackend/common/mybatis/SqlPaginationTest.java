package com.you.youbackend.common.mybatis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.you.youbackend.album.mapper.AlbumMomentMapper;
import com.you.youbackend.gathering.mapper.GatheringMapper;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证自定义 SQL 与 Wrapper 拼接后的筛选、排序和 UUID 参数绑定，无需连接数据库。 */
class SqlPaginationTest {
    private MappedStatement statement;
    private final UUID userId = UUID.randomUUID();
    private final UUID objectId = UUID.randomUUID();
    private final LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Test
    void albumTimelineKeepsJoinOwnershipAndOptionalThreeFieldBoundary() {
        var mapper = mock(AlbumMomentMapper.class, CALLS_REAL_METHODS);
        doAnswer(call -> {
            QueryWrapper<?> query = call.getArgument(0);
            BoundSql bound = boundSql(AlbumMomentMapper.class, "selectTimelineRows", query);
            String sql = bound.getSql();
            assertTrue(sql.contains("JOIN moment m ON m.id = am.moment_id"));
            assertTrue(sql.contains("am.album_id = ?"));
            assertTrue(sql.contains("m.author_user_id = ?"));
            assertTrue(sql.contains("am.deleted_at IS NULL"));
            assertTrue(sql.contains("m.deleted_at IS NULL"));
            assertTrue(sql.contains("ORDER BY m.memory_time ASC,m.created_at ASC,m.id ASC"));
            assertEquals(query.getParamNameValuePairs().size() > 2, sql.contains("m.memory_time > ?"));
            if (sql.contains("m.memory_time > ?")) {
                assertTrue(sql.contains("m.created_at > ?"));
                assertTrue(sql.contains("m.id > ?"));
            }
            assertUuidBindings(bound, sql.contains("m.memory_time > ?") ? 3 : 2);
            return List.of();
        }).when(mapper).selectTimelineRows(any(), eq(21));
        // 首屏无边界；后续页保留原来的三个排序字段。
        mapper.selectTimelinePage(objectId, userId, null, null, null, 21);
        mapper.selectTimelinePage(objectId, userId, time, time, objectId, 21);
    }

    @Test
    void gatheringPageKeepsMembershipExistsAndOptionalDescendingBoundary() {
        var mapper = mock(GatheringMapper.class, CALLS_REAL_METHODS);
        doAnswer(call -> {
            QueryWrapper<?> query = call.getArgument(0);
            BoundSql bound = boundSql(GatheringMapper.class, "selectPageRows", query);
            String sql = bound.getSql();
            assertTrue(sql.contains("EXISTS (SELECT 1"));
            assertTrue(sql.contains("m.gathering_id = g.id"));
            assertTrue(sql.contains("m.user_id = ?"));
            assertTrue(sql.contains("m.deleted_at IS NULL"));
            assertTrue(sql.contains("g.deleted_at IS NULL"));
            assertTrue(sql.contains("ORDER BY g.created_at DESC,g.id DESC"));
            assertEquals(query.getParamNameValuePairs().size() > 1, sql.contains("g.created_at < ?"));
            if (sql.contains("g.created_at < ?")) assertTrue(sql.contains("g.id < ?"));
            assertUuidBindings(bound, sql.contains("g.created_at < ?") ? 2 : 1);
            return List.of();
        }).when(mapper).selectPageRows(any(), eq(21));
        // 两种请求都经过成员资格筛选，仅后续页添加时间和 ID 边界。
        mapper.selectPage(userId, null, null, 21);
        mapper.selectPage(userId, time, objectId, 21);
    }

    /** 用真实 MyBatis 映射生成 SQL，验证 ew 片段中的参数仍使用全局 UUID 处理器。 */
    private BoundSql boundSql(Class<?> mapper, String method, Wrapper<?> query) {
        var configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UuidTypeHandler.class);
        configuration.addMapper(mapper);
        statement = configuration.getMappedStatement(mapper.getName() + "." + method);
        return statement.getBoundSql(Map.of("ew", query, "rowCount", 21));
    }

    /** 检查 UUID 通过占位参数绑定，数量限制同样保留绑定参数。 */
    private void assertUuidBindings(BoundSql bound, int expectedCount) {
        // Wrapper 的 Object 参数在绑定 JDBC 时才按实际 UUID 类型选择全局处理器。
        PreparedStatement jdbc = mock(PreparedStatement.class);
        assertDoesNotThrow(() -> {
            new DefaultParameterHandler(statement, bound.getParameterObject(), bound).setParameters(jdbc);
            verify(jdbc, times(expectedCount)).setBytes(anyInt(), argThat(bytes -> bytes.length == 16));
            verify(jdbc).setInt(bound.getParameterMappings().size(), 21);
        });
        assertTrue(bound.getSql().contains("LIMIT ?"));
        assertFalse(bound.getSql().contains(userId.toString()));
    }
}
