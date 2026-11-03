package com.you.media.service;

import com.you.common.exception.BusinessException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Service
public class StorageService {
  private final S3Client s3;
  private final S3Presigner signer;
  private final String bucket;

  public StorageService(
      S3Client s3, S3Presigner signer, @Value("${storage.bucket:you-files}") String bucket) {
    this.s3 = s3;
    this.signer = signer;
    this.bucket = bucket;
  }

  public String putUrl(String key, String mime) {
    try {
      s3.headBucket(b -> b.bucket(bucket));
      return signer
          .presignPutObject(
              b ->
                  b.signatureDuration(Duration.ofMinutes(10))
                      .putObjectRequest(p -> p.bucket(bucket).key(key).contentType(mime)))
          .url()
          .toString();
    } catch (Exception e) {
      throw BusinessException.storage();
    }
  }

  public String getUrl(String key) {
    try {
      s3.headObject(b -> b.bucket(bucket).key(key));
      return signer
          .presignGetObject(
              b ->
                  b.signatureDuration(Duration.ofMinutes(5))
                      .getObjectRequest(p -> p.bucket(bucket).key(key)))
          .url()
          .toString();
    } catch (NoSuchKeyException e) {
      throw BusinessException.missing();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) throw BusinessException.missing();
      throw BusinessException.storage();
    } catch (Exception e) {
      throw BusinessException.storage();
    }
  }

  public byte[] snapshot(String source, String destination) {
    try {
      s3.copyObject(
          b ->
              b.destinationBucket(bucket)
                  .destinationKey(destination)
                  .copySource(bucket + "/" + source));
      var h = s3.headObject(b -> b.bucket(bucket).key(destination));
      if (h.contentLength() > 10485760)
        throw new BusinessException(413, "PAYLOAD_TOO_LARGE", "图片超过 10 MiB");
      try (var stream = s3.getObject(b -> b.bucket(bucket).key(destination))) {
        byte[] bytes = stream.readNBytes(10485761);
        if (bytes.length > 10485760)
          throw new BusinessException(413, "PAYLOAD_TOO_LARGE", "图片超过 10 MiB");
        return bytes;
      }
    } catch (BusinessException e) {
      throw e;
    } catch (S3Exception e) {
      if (e.statusCode() == 404) throw new BusinessException(400, "UPLOAD_NOT_READY", "文件尚未上传完成");
      throw BusinessException.storage();
    } catch (Exception e) {
      throw BusinessException.storage();
    }
  }

  public void discard(String key) {
    try {
      s3.deleteObject(b -> b.bucket(bucket).key(key));
    } catch (Exception ignored) {
      /* orphan cleanup may retry later */
    }
  }
}
