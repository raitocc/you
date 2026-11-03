package com.you.media.entity;

import java.time.LocalDateTime;
import java.util.UUID;

@lombok.Getter
@lombok.Setter
public class MediaFile {
  private UUID id;
  private UUID ownerUserId;
  private String storageKey;
  private String mimeType;
  private long byteSize;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime deletedAt;
}
