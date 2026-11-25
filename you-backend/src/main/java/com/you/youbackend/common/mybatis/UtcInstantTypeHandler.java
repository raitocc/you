package com.you.youbackend.common.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@MappedTypes(Instant.class)

public class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Instant value, JdbcType jdbcType) throws SQLException {
        LocalDateTime utcTime = LocalDateTime.ofInstant(value.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        statement.setObject(index, utcTime);
    }

    @Override
    public Instant getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        LocalDateTime dateTime = resultSet.getObject(columnName, LocalDateTime.class);
        return fromUtc(dateTime);
    }

    @Override
    public Instant getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        LocalDateTime dateTime = resultSet.getObject(columnIndex, LocalDateTime.class);
        return fromUtc(dateTime);
    }

    @Override
    public Instant getNullableResult(CallableStatement callableStatement, int columnIndex) throws SQLException {
        LocalDateTime dateTime = callableStatement.getObject(columnIndex, LocalDateTime.class);
        return fromUtc(dateTime);
    }

    private static Instant fromUtc(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
