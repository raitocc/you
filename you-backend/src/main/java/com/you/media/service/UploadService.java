package com.you.media.service;

import com.you.common.exception.BusinessException;
import com.you.common.util.Ids;
import com.you.media.entity.*;
import com.you.media.mapper.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
public class UploadService {
  private final StorageService storage;
  private final MediaMapper media;
  private final UploadMapper uploads;
  private final ImageValidator images;
  private final TransactionTemplate transaction;

  public UploadService(
      StorageService storage,
      MediaMapper media,
      ImageValidator images,
      UploadMapper uploads,
      PlatformTransactionManager manager) {
    this.storage = storage;
    this.media = media;
    this.images = images;
    this.uploads = uploads;
    this.transaction = new TransactionTemplate(manager);
  }

  private LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
  }

  public Map<String, Object> apply(UUID owner, String mime, long size) {
    if (size > 10485760) throw new BusinessException(413, "PAYLOAD_TOO_LARGE", "图片超过 10 MiB");
    if (size < 1) throw BusinessException.invalid();
    if (!Set.of("image/jpeg", "image/png", "image/webp").contains(mime))
      throw new BusinessException(415, "UNSUPPORTED_MEDIA_TYPE", "图片类型不支持");
    var p = new MediaUpload();
    p.setId(Ids.next());
    p.setOwnerUserId(owner);
    p.setMimeType(mime);
    p.setByteSize(size);
    p.setStorageKey("tmp/" + p.getId());
    p.setCreatedAt(now());
    p.setExpiresAt(p.getCreatedAt().plusMinutes(10));
    p.setRetainUntil(p.getExpiresAt().plusHours(24));
    String url = storage.putUrl(p.getStorageKey(), mime);
    try {
      uploads.insert(p);
    } catch (DataAccessException e) {
      throw BusinessException.storage();
    }
    return Map.of(
        "uploadId",
        p.getId(),
        "uploadUrl",
        url,
        "method",
        "PUT",
        "headers",
        Map.of("Content-Type", mime),
        "expiresAt",
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").format(p.getExpiresAt()));
  }

  public Map<String, Object> complete(UUID owner, UUID id) {
    try {
      return transaction.execute(
          status -> {
            var p = uploads.lock(id, owner);
            var current = now();
            if (p == null || !current.isBefore(p.getRetainUntil()))
              throw BusinessException.missing();
            if (p.getFileId() != null) {
              var f = media.find(p.getFileId());
              if (f == null) throw BusinessException.missing();
              return result(f);
            }
            if (!current.isBefore(p.getExpiresAt()))
              throw new BusinessException(410, "UPLOAD_EXPIRED", "上传凭据已过期");
            String key = "images/" + Ids.next();
            // Storage cleanup follows the database outcome; an unknown commit outcome must not
            // delete a potentially committed file.
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  public void afterCompletion(int completion) {
                    if (completion == STATUS_ROLLED_BACK) storage.discard(key);
                  }

                  public void afterCommit() {
                    storage.discard(p.getStorageKey());
                  }
                });
            byte[] bytes = storage.snapshot(p.getStorageKey(), key);
            String mime = images.validate(bytes, p.getMimeType(), p.getByteSize());
            var f = new MediaFile();
            f.setId(Ids.next());
            f.setOwnerUserId(owner);
            f.setStorageKey(key);
            f.setMimeType(mime);
            f.setByteSize(bytes.length);
            f.setCreatedAt(now());
            f.setUpdatedAt(f.getCreatedAt());
            media.insert(f);
            p.setFileId(f.getId());
            p.setCompletedAt(now());
            p.setRetainUntil(p.getCompletedAt().plusHours(24));
            uploads.complete(p);
            return result(f);
          });
    } catch (org.springframework.transaction.TransactionException | DataAccessException e) {
      throw BusinessException.storage();
    }
  }

  private Map<String, Object> result(MediaFile f) {
    return Map.of("fileId", f.getId(), "mimeType", f.getMimeType(), "byteSize", f.getByteSize());
  }

  @Scheduled(fixedDelay = 600000)
  public void cleanup() {
    var time = now();
    for (var p : uploads.expired(time)) {
      if (uploads.deleteExpired(p.getId(), time) == 1) storage.discard(p.getStorageKey());
    }
  }
}
