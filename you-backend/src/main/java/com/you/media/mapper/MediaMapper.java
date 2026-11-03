package com.you.media.mapper;

import com.you.media.entity.MediaFile;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MediaMapper {
  @Select("SELECT * FROM media_file WHERE id=#{id} AND deleted_at IS NULL")
  MediaFile find(UUID id);

  @Insert(
      "INSERT INTO"
          + " media_file(id,owner_user_id,storage_key,mime_type,byte_size,created_at,updated_at)"
          + " VALUES(#{id},#{ownerUserId},#{storageKey},#{mimeType},#{byteSize},#{createdAt},#{updatedAt})")
  int insert(MediaFile file);
}
