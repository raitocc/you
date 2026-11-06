package com.you.youbackend.moment.mapper;

import com.you.youbackend.moment.model.MomentMood;
import org.apache.ibatis.type.EnumTypeHandler;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 读取历史中文心情，新数据仍按枚举名称写入。
 */
public class MomentMoodTypeHandler extends EnumTypeHandler<MomentMood> {

    public MomentMoodTypeHandler() {
        super(MomentMood.class);
    }

    @Override
    public MomentMood getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return readMood(resultSet.getString(columnName));
    }

    @Override
    public MomentMood getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return readMood(resultSet.getString(columnIndex));
    }

    @Override
    public MomentMood getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return readMood(statement.getString(columnIndex));
    }

    /** 将旧记录中的中文心情还原为业务枚举。 */
    private MomentMood readMood(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "开心" -> MomentMood.HAPPY;
            case "平静" -> MomentMood.CALM;
            case "松弛" -> MomentMood.RELAXED;
            case "心动" -> MomentMood.SMITTEN;
            case "惊喜" -> MomentMood.SURPRISED;
            case "自在" -> MomentMood.CAREFREE;
            case "低落" -> MomentMood.LOW;
            case "焦虑" -> MomentMood.ANXIOUS;
            default -> MomentMood.valueOf(value);
        };
    }
}
