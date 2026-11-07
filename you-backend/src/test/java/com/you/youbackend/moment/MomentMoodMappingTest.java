package com.you.youbackend.moment;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.you.youbackend.moment.model.MomentMood;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证移除兼容处理器后，MyBatis-Plus 默认枚举映射仍按名称读写心情。 */
class MomentMoodMappingTest {
    @Test
    void defaultHandlerReadsAndWritesAllMoodNames() throws Exception {
        var handler = new MybatisConfiguration().getTypeHandlerRegistry().getTypeHandler(MomentMood.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        for (MomentMood mood : MomentMood.values()) {
            handler.setParameter(statement, 1, mood, JdbcType.VARCHAR);
            verify(statement).setObject(1, mood.name(), JdbcType.VARCHAR.TYPE_CODE);
            when(result.getString("mood")).thenReturn(mood.name());
            assertEquals(mood, handler.getResult(result, "mood"));
        }
        when(result.getString("mood")).thenReturn(null);
        assertNull(handler.getResult(result, "mood"));
    }
}
