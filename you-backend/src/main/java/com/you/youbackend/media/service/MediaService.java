package com.you.youbackend.media.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.media.entity.MediaFile;
import com.you.youbackend.media.mapper.MediaFileMapper;
import com.you.youbackend.media.request.CreateUploadRequest;
import com.you.youbackend.media.event.MediaUploadEvent;
import com.you.youbackend.media.request.TestUploadRequest;
import com.you.youbackend.media.response.CreateUploadResponse;
import com.you.youbackend.media.response.TestUploadResponse;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.you.youbackend.media.response.UploadStatusResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
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
    private static final Duration READ_URL_VALIDITY = Duration.ofMinutes(10);

    public static final String STORAGE_KEY_PREFIX = "images/";
    private static final long MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/webp");

    private final S3Presigner presigner;
    private final String bucket;
    private final String uploadUrlPrefix;
    private final RestClient httpClient;
    private final MediaFileMapper mediaFileMapper;
    private final S3Client s3Client;

    /**
     * 配置对象存储访问及测试上传所用的 HTTP 客户端。
     */
    public MediaService(
            S3Presigner presigner,
            @Value("${storage.bucket}") String bucket,
            @Value("${storage.endpoint}") String endpoint,
            MediaFileMapper mediaFileMapper,
            S3Client s3Client) {
        this.presigner = presigner;
        this.bucket = bucket;
        this.mediaFileMapper = mediaFileMapper;
        this.uploadUrlPrefix = URI.create(endpoint).resolve("/" + bucket + "/").toString();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.httpClient = RestClient.builder().requestFactory(factory).build();
        this.s3Client = s3Client;
    }

    /**
     * 为客户端声明的图片生成临时上传地址。
     */
    public CreateUploadResponse createUpload(CreateUploadRequest request, UUID userId) {
        UUID fileUuid = UuidCreator.getTimeOrderedEpoch();
        String storageKey = STORAGE_KEY_PREFIX + userId + "/" + fileUuid;
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .contentType(request.getMimeType().toString())
                .build();
        PresignedPutObjectRequest signedRequest = presigner.presignPutObject(
                builder -> builder
                        .signatureDuration(UPLOAD_URL_VALIDITY)
                        .putObjectRequest(putRequest));
        log.info("上传地址已生成，userId={}，fileId={}，storageKey={}，mimeType={}，declaredByteSize={}",
                userId, fileUuid, storageKey, request.getMimeType(), request.getByteSize());
        return new CreateUploadResponse(fileUuid, signedRequest.url().toString());
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
            throw BusinessException.invalidArgument("请使用当前 RustFS 桶的上传地址");
        }
        MultipartFile file = request.getFile();
        if (file.isEmpty() || !StringUtils.hasText(file.getContentType())) {
            log.debug("测试上传被拒绝，reason=文件为空或缺少Content-Type，byteSize={}", file.getSize());
            throw BusinessException.invalidArgument("请选择有内容且携带 Content-Type 的文件");
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
                throw BusinessException.badGateway("UPLOAD_FAILED", "RustFS 上传失败：" + response.getStatusCode());
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
            throw BusinessException.badGateway("UPLOAD_FAILED", "RustFS 上传失败：" + detail, exception);
        }
    }

    /**
     * 为已确认有权读取的文件生成临时访问地址。
     *
     * @param storageKey 文件在对象存储中的完整键，例如 images/文件UUID
     * @return 有效期 10 分钟的 GET 签名地址
     */
    public String createReadUrl(String storageKey) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .build();

        return presigner.presignGetObject(builder -> builder
                        .signatureDuration(READ_URL_VALIDITY)
                        .getObjectRequest(getRequest))
                .url()
                .toString();
    }

    /**
     * 根据文件 ID 生成临时读取地址。
     * 调用方负责确认业务读取权限，文件记录不存在时返回 null。
     */
    public String getReadUrl(UUID fileId) {
        MediaFile file = mediaFileMapper.selectById(fileId);
        if (file == null) {
            log.debug("文件读取地址未生成，fileId={}，reason=文件未登记或已删除", fileId);
            return null;
        }
        return createReadUrl(file.getStorageKey());
    }

    /**
     * 批量生成文件读取地址，调用方负责确认每个文件的业务读取资格。
     * @param fileIds 已授权展示的文件 ID 集合，允许为空
     * @return 有效文件 ID 到签名 URL 的映射，文件失效时省略该项
     */
    public Map<UUID, String> getReadUrls(Collection<UUID> fileIds) {
        // 一次读取对象键后本地计算签名，空集合直接返回。
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        return mediaFileMapper.selectStorageKeys(fileIds).stream()
                .collect(Collectors.toMap(MediaFile::getId, file -> createReadUrl(file.getStorageKey())));
    }

    /**
     * 处理 RustFS 上传事件，检查并登记文件元数据。
     */
    @Transactional
    public void handleUploadEvent(MediaUploadEvent request) {
        log.info("收到文件上传事件，recordCount={}", request.records().size());
        for (MediaUploadEvent.EventRecord event : request.records()) {
            boolean isUpload = "s3:ObjectCreated:Put".equals(event.eventName())
                    || "ObjectCreated:Put".equals(event.eventName());

            if (isUpload && bucket.equals(event.s3().bucket().name())) {
                String storageKey = URLDecoder.decode(
                        event.s3().object().key(),
                        StandardCharsets.UTF_8);

                registerFile(storageKey);
            } else {
                log.info("忽略无关存储事件，eventName={}，bucket={}",
                        event.eventName(), event.s3().bucket().name());
            }
        }
    }

    /**
     * 查询对象元数据，并登记符合上传要求的图片。
     */
    private void registerFile(String storageKey) {
        // 对应 images/{用户ID}/{文件ID}。
        String[] parts = storageKey.split("/", -1);

        if (parts.length != 3 || !"images".equals(parts[0])) {
            log.warn("忽略非业务文件事件，storageKey={}", storageKey);
            return;
        }

        UUID userId = UUID.fromString(parts[1]);
        UUID fileId = UUID.fromString(parts[2]);

        // 重复投递时复用已有记录。
        if (mediaFileMapper.selectById(fileId) != null) {
            log.debug("文件记录已存在，忽略重复登记，fileId={}", fileId);
            return;
        }

        HeadObjectResponse metadata;
        try {
            metadata = s3Client.headObject(request -> request
                    .bucket(bucket)
                    .key(storageKey));
        } catch (SdkException exception) {
            log.error("查询上传文件元数据失败，userId={}，fileId={}，exceptionType={}",
                    userId, fileId, exception.getClass().getSimpleName());
            throw exception;
        }

        long byteSize = metadata.contentLength();
        String mimeType = metadata.contentType();

        if (byteSize <= 0 || byteSize > MAX_UPLOAD_BYTES
                || !ALLOWED_IMAGE_TYPES.contains(mimeType)) {

            log.warn("文件未通过上传检查，fileId={}，byteSize={}，mimeType={}",
                    fileId, byteSize, mimeType);
            return;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);

        MediaFile file = new MediaFile();
        file.setId(fileId);
        file.setOwnerUserId(userId);
        file.setStorageKey(storageKey);
        file.setMimeType(mimeType);
        file.setByteSize(byteSize);
        file.setCreatedAt(now);
        file.setUpdatedAt(now);

        try {
            mediaFileMapper.insert(file);
            // 此处记录事务内的写入步骤，最终提交由 handleUploadEvent 的事务完成。
            log.info("写入文件元数据，userId={}，fileId={}，byteSize={}，mimeType={}",
                    userId, fileId, byteSize, mimeType);
        } catch (DuplicateKeyException exception) {
            // 并发重复通知由数据库唯一约束兜底，保留已有记录。
            log.debug("文件记录已存在，忽略重复登记，fileId={}", fileId);
        }
    }

    /**
     * 验证文件是当前用户上传的有效图片。
     *
     * @param fileId 文件 ID
     * @param userId 当前用户 ID
     */
    public void validateOwnedImage(UUID fileId, UUID userId) {
        requireOwnedImage(fileId, userId);
    }

    /**
     * 校验图片归属并生成临时读取地址。
     */
    public String createOwnedImageReadUrl(UUID fileId, UUID userId) {
        return createReadUrl(requireOwnedImage(fileId, userId).getStorageKey());
    }

    /**
     * 批量取得本人有效图片的对象键，供列表筛选和封面签名。
     */
    public Map<UUID, String> findOwnedImageKeys(UUID userId, Collection<UUID> fileIds) {
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        return mediaFileMapper.selectOwnedStorageKeys(userId, fileIds, ALLOWED_IMAGE_TYPES)
                .stream().collect(Collectors.toMap(MediaFile::getId, MediaFile::getStorageKey));
    }

    /**
     * 读取当前用户已入库的有效图片，校验失败时抛出业务异常。
     */
    private MediaFile requireOwnedImage(UUID fileId, UUID userId) {
        MediaFile file = mediaFileMapper.selectById(fileId);

        if (file == null
                || !userId.equals(file.getOwnerUserId())
                || !ALLOWED_IMAGE_TYPES.contains(file.getMimeType())) {

            String reason = "图片类型不支持";
            if (file == null) {
                reason = "文件未登记或已删除";
            } else if (!userId.equals(file.getOwnerUserId())) {
                reason = "文件不属于当前用户";
            }
            log.warn("图片文件校验失败，userId={}，fileId={}，reason={}", userId, fileId, reason);
            throw BusinessException.badRequest("FILE_NOT_USABLE", "请选择已上传完成的本人图片");
        }
        return file;
    }

    /**
     * 查询当前用户的图片是否已经登记完成。
     *
     * @param fileId 文件 ID
     * @param userId 当前登录用户 ID
     * @return 文件上传就绪状态
     */
    public UploadStatusResponse getUploadStatus(UUID fileId, UUID userId) {
        boolean ready = mediaFileMapper.existsOwnedFile(fileId, userId);
        log.debug("查询上传就绪状态，userId={}，fileId={}，ready={}", userId, fileId, ready);
        return new UploadStatusResponse(fileId, ready);
    }
}
