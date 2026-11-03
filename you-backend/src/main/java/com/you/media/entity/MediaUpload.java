package com.you.media.entity;

import java.time.LocalDateTime;
import java.util.UUID;

@lombok.Getter
@lombok.Setter
public class MediaUpload {
  private UUID id, ownerUserId, fileId;
  private String storageKey, mimeType;
  private long byteSize;
  private LocalDateTime expiresAt, retainUntil, completedAt, createdAt;
}
