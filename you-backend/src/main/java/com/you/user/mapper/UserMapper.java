package com.you.user.mapper;

import com.you.user.entity.User;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
  @Select("SELECT * FROM app_user WHERE id=#{id} AND deleted_at IS NULL")
  User find(UUID id);

  @Select("SELECT * FROM app_user WHERE username=#{username} AND deleted_at IS NULL")
  User byUsername(String username);

  @Insert(
      "INSERT INTO app_user(id,username,password_hash,nickname,created_at,updated_at)"
          + " VALUES(#{id},#{username},#{passwordHash},#{nickname},#{createdAt},#{updatedAt})")
  int insert(User user);

  @Update(
      "UPDATE app_user SET"
          + " nickname=#{nickname},avatar_file_id=#{avatarFileId},updated_at=#{updatedAt} WHERE"
          + " id=#{id} AND deleted_at IS NULL")
  int update(User user);
}
