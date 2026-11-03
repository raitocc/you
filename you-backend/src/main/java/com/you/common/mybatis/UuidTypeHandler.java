package com.you.common.mybatis;

import java.nio.ByteBuffer;
import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.type.*;

@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
  public void setNonNullParameter(PreparedStatement s, int i, UUID u, JdbcType t)
      throws SQLException {
    s.setBytes(
        i,
        ByteBuffer.allocate(16)
            .putLong(u.getMostSignificantBits())
            .putLong(u.getLeastSignificantBits())
            .array());
  }

  private UUID read(byte[] b) {
    if (b == null) return null;
    var x = ByteBuffer.wrap(b);
    return new UUID(x.getLong(), x.getLong());
  }

  public UUID getNullableResult(ResultSet r, String c) throws SQLException {
    return read(r.getBytes(c));
  }

  public UUID getNullableResult(ResultSet r, int c) throws SQLException {
    return read(r.getBytes(c));
  }

  public UUID getNullableResult(CallableStatement s, int c) throws SQLException {
    return read(s.getBytes(c));
  }
}
