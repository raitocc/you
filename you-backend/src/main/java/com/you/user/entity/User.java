package com.you.user.entity;

import java.time.LocalDateTime;
import java.util.UUID;

@lombok.Getter
@lombok.Setter
public class User {
  private UUID id;
  private String username;
  private String passwordHash;
  private String nickname;
  private UUID avatarFileId;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime deletedAt;
}
