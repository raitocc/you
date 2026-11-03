package com.you.user.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AvatarAccessMapper {
  @Select(
      """
      SELECT EXISTS(
      SELECT 1 FROM gathering_member a JOIN gathering_member b ON a.gathering_id=b.gathering_id
      JOIN gathering g ON g.id=a.gathering_id
      WHERE a.user_id=#{viewer} AND b.user_id=#{target} AND a.deleted_at IS NULL AND b.deleted_at IS NULL AND g.deleted_at IS NULL
      UNION ALL
      SELECT 1 FROM circle_member a JOIN circle_member b ON a.circle_id=b.circle_id JOIN circle c ON c.id=a.circle_id
      WHERE a.user_id=#{viewer} AND b.user_id=#{target} AND a.deleted_at IS NULL AND b.deleted_at IS NULL AND c.deleted_at IS NULL
      UNION ALL
      SELECT 1 FROM moment m WHERE m.author_user_id=#{target} AND m.deleted_at IS NULL AND (
      EXISTS(SELECT 1 FROM gathering g JOIN gathering_member gm ON gm.gathering_id=g.id WHERE g.id=m.gathering_id AND g.deleted_at IS NULL AND gm.user_id=#{viewer} AND gm.deleted_at IS NULL)
      OR EXISTS(SELECT 1 FROM circle_share s JOIN circle c ON c.id=s.circle_id JOIN circle_member cm ON cm.circle_id=c.id
      WHERE c.deleted_at IS NULL AND cm.user_id=#{viewer} AND cm.deleted_at IS NULL AND s.deleted_at IS NULL AND (
      (s.object_type='moment' AND s.object_id=m.id)

      ))) )
      """)
  boolean allowed(@Param("viewer") UUID viewer, @Param("target") UUID target);
}
