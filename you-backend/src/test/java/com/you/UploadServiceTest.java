package com.you;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.you.common.exception.BusinessException;
import com.you.media.mapper.*;
import com.you.media.service.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class UploadServiceTest {
  @Test
  void failedCredentialPersistenceReturnsServiceUnavailable() {
    var storage = mock(StorageService.class);
    var uploads = mock(UploadMapper.class);
    when(storage.putUrl(anyString(), eq("image/png"))).thenReturn("https://example.test/upload");
    when(uploads.insert(any()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
    var service =
        new UploadService(
            storage,
            mock(MediaMapper.class),
            new ImageValidator(),
            uploads,
            mock(PlatformTransactionManager.class));
    assertEquals(
        503,
        assertThrows(
                BusinessException.class, () -> service.apply(UUID.randomUUID(), "image/png", 2))
            .status);
  }
}
