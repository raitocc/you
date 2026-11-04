package com.you.youbackend.media.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.request.CreateUploadRequest;
import com.you.youbackend.media.request.TestUploadRequest;
import com.you.youbackend.media.response.CreateUploadResponse;
import com.you.youbackend.media.response.TestUploadResponse;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * 媒体上传地址生成与签名上传测试。
 */
@Log4j2
@Service
public class MediaService {

    private static final Duration UPLOAD_URL_VALIDITY = Duration.ofMinutes(10);
    public static final String STORAGE_KEY_PREFIX = "images/";

    private final S3Presigner presigner;
    private final String bucket;
    private final String uploadUrlPrefix;
    private final RestClient httpClient;

    public MediaService(
            S3Presigner presigner,
            @Value("${storage.bucket}") String bucket,
            @Value("${storage.endpoint}") String endpoint) {
        this.presigner = presigner;
        this.bucket = bucket;
        this.uploadUrlPrefix = URI.create(endpoint).resolve("/" + bucket + "/").toString();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.httpClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 为客户端声明的图片生成临时上传地址。
     */
    public CreateUploadResponse createUpload(CreateUploadRequest request) {
        UUID uuid = UuidCreator.getTimeOrderedEpoch();
        String storageKey = STORAGE_KEY_PREFIX + uuid;
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .contentType(request.getMimeType().toString())
                .build();
        PresignedPutObjectRequest signedRequest = presigner.presignPutObject(
                builder -> builder
                        .signatureDuration(UPLOAD_URL_VALIDITY)
                        .putObjectRequest(putRequest));
        log.info("上传地址已生成，storageKey={}，mimeType={}，declaredByteSize={}",
                storageKey, request.getMimeType(), request.getByteSize());
        return new CreateUploadResponse(signedRequest.url().toString());
    }

    /**
     * 将表单中的文件按原 Content-Type 通过 PUT 上传至签名地址。
     *
     * @param request 签名地址及待上传文件
     * @return RustFS 返回的状态码和实体标签
     */
    public TestUploadResponse testUpload(TestUploadRequest request) {
        if (!request.getUrl().startsWith(uploadUrlPrefix)) {
            log.debug("测试上传被拒绝，reason=目标地址不属于当前存储桶");
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "请使用当前 RustFS 桶的上传地址");
        }
        MultipartFile file = request.getFile();
        if (file.isEmpty() || !StringUtils.hasText(file.getContentType())) {
            log.debug("测试上传被拒绝，reason=文件为空或缺少Content-Type，byteSize={}", file.getSize());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "请选择有内容且携带 Content-Type 的文件");
        }

        try {
            ResponseEntity<Void> response = httpClient.put()
                    .uri(URI.create(request.getUrl()))
                    .header("Content-Type", file.getContentType())
                    .body(file.getResource())
                    .retrieve()
                    .toBodilessEntity();
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("测试上传失败，statusCode={}，byteSize={}",
                        response.getStatusCode().value(), file.getSize());
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "UPLOAD_FAILED", "RustFS 上传失败：" + response.getStatusCode());
            }
            log.info("测试上传成功，statusCode={}，byteSize={}",
                    response.getStatusCode().value(), file.getSize());
            return new TestUploadResponse(response.getStatusCode().value(), response.getHeaders().getETag());
        } catch (RestClientException exception) {
            String detail = exception instanceof RestClientResponseException responseError
                    ? responseError.getResponseBodyAsString() : "文件读取或连接失败";
            // HTTP 客户端异常消息可能包含完整签名地址，日志仅记录诊断字段。
            log.error("测试上传失败，statusCode={}，byteSize={}，exceptionType={}",
                    exception instanceof RestClientResponseException responseError
                            ? responseError.getStatusCode().value() : null,
                    file.getSize(), exception.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "UPLOAD_FAILED", "RustFS 上传失败：" + detail, exception);
        }
    }
}
