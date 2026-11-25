package com.you.youbackend.media.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.you.youbackend.common.exception.BusinessException;
import com.you.youbackend.common.util.TimeUtils;
import com.you.youbackend.media.entity.MediaFile;
import com.you.youbackend.media.event.MediaUploadEvent;
import com.you.youbackend.media.mapper.MediaFileMapper;
import com.you.youbackend.media.request.CreateUploadRequest;
import com.you.youbackend.media.response.CreateUploadResponse;
import com.you.youbackend.media.response.UploadStatusResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

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
     * 按对象键生成 10 分钟读取地址，不查库、不校验资格，读取权限由调用方确认。
     *
     * @param storageKey 文件在对象存储中的完整键，例如 images/{userId}/{fileId}
     * @return 预签名 GET 地址
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
     * 根据文件 ID 生成 10 分钟读取地址，不校验归属、不过滤类型，失效返回 null。读取资格由调用方确认。
     */
    public String getReadUrl(UUID fileId) {
        Objects.requireNonNull(fileId, "fileId 必须非空");
        MediaFile file = mediaFileMapper.selectById(fileId);
        if (file == null) {
            log.debug("文件读取地址未生成，fileId={}，reason=文件未登记或已删除", fileId);
            return null;
        }
        return createReadUrl(file.getStorageKey());
    }

    /**
     * 批量生成文件读取地址，不校验归属、不过滤类型，调用方负责确认每个文件的业务读取资格。
     *
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

        Instant now = TimeUtils.now();

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
     * 验证文件是当前用户上传的有效图片；fileId 传 null 表示不带图（无封面、清头像等），直接放行。
     *
     * <p>只读查询，无副作用。</p>
     *
     * @param fileId 文件 ID，可为 null
     * @param userId 当前用户 ID
     * @throws BusinessException 文件未登记、已删除、非本人或 MIME 不在白名单时抛出（{@code FILE_NOT_USABLE}）；null 不会抛
     */
    public void validateOwnedImage(UUID fileId, UUID userId) {
        if (fileId == null) {
            return;
        }
        getOwnedImage(fileId, userId);
    }

    public Map<UUID, String> findImageKeys(Collection<UUID> fileIds) {
        // 空集合跳过查询；上传完成后才登记媒体记录，逻辑删除由全局配置过滤。
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        return mediaFileMapper.selectImageStorageKeys(fileIds, ALLOWED_IMAGE_TYPES)
                .stream().collect(Collectors.toMap(MediaFile::getId, MediaFile::getStorageKey));
    }

    /**
     * 批量取本人有效图片的对象键：校验归属并过滤 MIME，失效的直接丢弃、不报错。
     * 数量是否齐全由调用方比对（要求全部可用时检查返回数量）。
     *
     * <p>只读查询，无副作用。</p>
     *
     * @param userId  当前用户 ID
     * @param fileIds 文件 ID 集合，非 null，可为空集合
     * @return 文件 ID 到对象键的映射，失效项不出现；空入参返回空映射
     */
    public Map<UUID, String> getOwnedImageKeys(UUID userId, Collection<UUID> fileIds) {
        Objects.requireNonNull(fileIds, "fileIds 不能为 null");
        return mediaFileMapper.selectOwnedStorageKeys(userId, fileIds, ALLOWED_IMAGE_TYPES)
                .stream().collect(Collectors.toMap(MediaFile::getId, MediaFile::getStorageKey));
    }

    /**
     * 读取本人已入库的有效图片，未登记、非本人或类型不支持直接抛。不接受 null 入参。
     *
     * <p>只读查询，无副作用。</p>
     *
     * @param fileId 文件 ID
     * @param userId 当前用户 ID
     * @return 完整的媒体文件记录
     * @throws BusinessException 文件未登记、已删除、非本人或 MIME 不在白名单时抛出（{@code FILE_NOT_USABLE}）
     */
    public MediaFile getOwnedImage(UUID fileId, UUID userId) {
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

    public UploadStatusResponse getUploadStatus(UUID fileId, UUID userId) {
        boolean ready = mediaFileMapper.existsOwnedFile(fileId, userId, ALLOWED_IMAGE_TYPES);
        log.debug("查询上传就绪状态，userId={}，fileId={}，ready={}", userId, fileId, ready);
        return new UploadStatusResponse(fileId, ready);
    }

}
