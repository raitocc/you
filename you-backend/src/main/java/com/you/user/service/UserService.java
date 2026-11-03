package com.you.user.service;

import com.you.common.exception.BusinessException;
import com.you.common.util.*;
import com.you.media.mapper.MediaMapper;
import com.you.user.entity.User;
import com.you.user.mapper.UserMapper;
import java.time.*;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
  private final UserMapper users;
  private final MediaMapper media;
  private final Pbkdf2PasswordEncoder encoder =
      Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  private final String dummy = encoder.encode("DummyPassword123");

  public UserService(UserMapper users, MediaMapper media) {
    this.users = users;
    this.media = media;
  }

  public User register(String username, String password, String nickname) {
    username = Input.username(username);
    nickname = Input.nickname(nickname);
    int n = password.codePointCount(0, password.length());
    if (n < 8
        || n > 64
        || !password.matches("(?s).*[a-zA-Z].*")
        || !password.matches("(?s).*[0-9].*")) throw BusinessException.invalid();
    User u = new User();
    u.setId(Ids.next());
    u.setUsername(username);
    u.setNickname(nickname);
    u.setPasswordHash(encoder.encode(password));
    u.setCreatedAt(
        LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    u.setUpdatedAt(u.getCreatedAt());
    try {
      users.insert(u);
    } catch (DuplicateKeyException e) {
      throw new BusinessException(409, "ACCOUNT_UNAVAILABLE", "账号不可用，调整账号或尝试登录");
    }
    return u;
  }

  public User login(String username, String password) {
    User u = users.byUsername(username);
    boolean valid = encoder.matches(password, u == null ? dummy : u.getPasswordHash());
    if (u == null || !valid) throw new BusinessException(401, "INVALID_CREDENTIALS", "账号或密码不正确");
    return u;
  }

  public User current(UUID id) {
    User u = users.find(id);
    if (u == null) throw BusinessException.unauthorized();
    return u;
  }

  public User update(UUID id, String nickname, UUID fileId) {
    User u = current(id);
    if (fileId != null) {
      var f = media.find(fileId);
      if (f == null
          || !id.equals(f.getOwnerUserId())
          || !java.util.Set.of("image/jpeg", "image/png", "image/webp").contains(f.getMimeType()))
        throw new BusinessException(400, "FILE_NOT_USABLE", "头像文件不可用");
    }
    u.setNickname(Input.nickname(nickname));
    u.setAvatarFileId(fileId);
    u.setUpdatedAt(
        LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    if (users.update(u) != 1) throw BusinessException.unauthorized();
    return current(id);
  }
}
