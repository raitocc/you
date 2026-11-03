package com.you.media.mapper;

import com.you.media.entity.MediaUpload;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UploadMapper {
  @Insert(
      "INSERT INTO"
          + " media_upload(id,owner_user_id,storage_key,mime_type,byte_size,expires_at,retain_until,created_at)"
          + " VALUES(#{id},#{ownerUserId},#{storageKey},#{mimeType},#{byteSize},#{expiresAt},#{retainUntil},#{createdAt})")
  int insert(MediaUpload upload);

  @Select("SELECT * FROM media_upload WHERE id=#{id} AND owner_user_id=#{owner} FOR UPDATE")
  MediaUpload lock(@Param("id") UUID id, @Param("owner") UUID owner);

  @Update(
      "UPDATE media_upload SET"
          + " file_id=#{fileId},completed_at=#{completedAt},retain_until=#{retainUntil} WHERE"
          + " id=#{id}")
  int complete(MediaUpload upload);

  @Select("SELECT * FROM media_upload WHERE retain_until <= #{now} ORDER BY retain_until LIMIT 100")
  List<MediaUpload> expired(LocalDateTime now);

  @Delete("DELETE FROM media_upload WHERE id=#{id} AND retain_until <= #{now}")
  int deleteExpired(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
