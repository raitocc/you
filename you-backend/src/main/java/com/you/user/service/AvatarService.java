package com.you.user.service;

import com.you.common.exception.BusinessException;
import com.you.media.mapper.MediaMapper;
import com.you.media.service.StorageService;
import com.you.user.mapper.*;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AvatarService {
  private final UserMapper users;
  private final AvatarAccessMapper access;
  private final MediaMapper media;
  private final StorageService storage;

  public AvatarService(UserMapper u, AvatarAccessMapper a, MediaMapper m, StorageService s) {
    users = u;
    access = a;
    media = m;
    storage = s;
  }

  public String url(UUID viewer, UUID target) {
    var u = users.find(target);
    if (u == null
        || u.getAvatarFileId() == null
        || (!viewer.equals(target) && !access.allowed(viewer, target)))
      throw BusinessException.missing();
    var file = media.find(u.getAvatarFileId());
    if (file == null) throw BusinessException.missing();
    return storage.getUrl(file.getStorageKey());
  }
}
