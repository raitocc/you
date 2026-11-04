package com.you.youbackend.common.mybatis;

import java.nio.ByteBuffer;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * 在 Java UUID 与数据库 BINARY(16) 之间转换。
 */
@MappedTypes(UUID.class)
@MappedJdbcTypes(value = JdbcType.BINARY, includeNullJdbcType = true)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int index,
            UUID value,
            JdbcType jdbcType) throws SQLException {

        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();

        statement.setBytes(index, bytes);
    }

    @Override
    public UUID getNullableResult(
            ResultSet resultSet, String columnName) throws SQLException {

        return fromBytes(resultSet.getBytes(columnName));
    }

    @Override
    public UUID getNullableResult(
            ResultSet resultSet, int columnIndex) throws SQLException {

        return fromBytes(resultSet.getBytes(columnIndex));
    }

    @Override
    public UUID getNullableResult(
            CallableStatement statement, int columnIndex) throws SQLException {

        return fromBytes(statement.getBytes(columnIndex));
    }

    private static UUID fromBytes(byte[] bytes) {
        if (bytes == null) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}