package com.you.user.vo;

import com.you.user.entity.User;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public record UserView(
    UUID id,
    String username,
    String nickname,
    UUID avatarFileId,
    String avatarContentPath,
    String createdAt,
    String updatedAt) {
  public static UserView of(User u) {
    var f = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'");
    return new UserView(
        u.getId(),
        u.getUsername(),
        u.getNickname(),
        u.getAvatarFileId(),
        u.getAvatarFileId() == null ? null : "/user/users/" + u.getId() + "/avatar",
        u.getCreatedAt().format(f),
        u.getUpdatedAt().format(f));
  }
}
