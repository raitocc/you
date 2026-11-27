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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 图片文件的写入前校验、读取地址签名与上传登记。
 */
@Log4j2
@Service
public class MediaService {

    /** 预签名上传/读取地址的有效期。 */
    private static final Duration UPLOAD_URL_VALIDITY = Duration.ofMinutes(10);
    private static final Duration READ_URL_VALIDITY = Duration.ofMinutes(10);

    /** 对象键统一放在 images/ 前缀下，形状 images/{userId}/{fileId}。 */
    public static final String STORAGE_KEY_PREFIX = "images/";

    /** 上传大小上限，登记时按对象存储里的真实大小复核。 */
    private static final long MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    /** 允许的图片 MIME 白名单。 */
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/webp");

    private final S3Presigner presigner;
    private final String bucket;
    private final MediaFileMapper mediaFileMapper;
    private final S3Client s3Client;

    public MediaService(
            S3Presigner presigner,
            @Value("${storage.bucket}") String bucket,
            MediaFileMapper mediaFileMapper,
            S3Client s3Client) {
        this.presigner = presigner;
        this.bucket = bucket;
        this.mediaFileMapper = mediaFileMapper;
        this.s3Client = s3Client;
    }

    /**
     * 校验一张图片是当前用户上传的有效图片；fileId 传 null 表示不带图（无封面、清头像等），直接放行。
     *
     * <p>流程：null 早退 → 委托批量校验。只读查询，无副作用。</p>
     *
     * @param fileId 文件 ID，可为 null
     * @param userId 当前用户 ID
     * @throws BusinessException 文件未登记、已删除、非本人或 MIME 不在白名单时抛出（{@code FILE_NOT_USABLE}）；null 不会抛
     */
    public void validateOwnedImage(UUID fileId, UUID userId) {
        // 1. 不带图的场景直接放行（无封面、清头像）
        if (fileId == null) {
            return;
        }
        // 2. 其余必须是本人已入库的有效图片，不合格直接抛
        validateOwnedImages(List.of(fileId), userId);
    }

    /**
     * 批量校验图片全部是当前用户上传的有效图片，缺一张就整体拒绝。写入建关联前的把关，只看可用性、不做任何写入。
     *
     * <p>流程：空集合放行 → 一条查询按归属和 MIME 白名单过滤 → 数量对不上就是有坏图。
     * 只读查询，无副作用。</p>
     *
     * @param fileIds 文件 ID 集合，非 null，可为空集合；重复 ID 去重后计数，不会误判
     * @param userId  当前用户 ID
     * @throws BusinessException 任一图片未登记、已删除、非本人或 MIME 不在白名单时抛出（{@code FILE_NOT_USABLE}）
     */
    public void validateOwnedImages(Collection<UUID> fileIds, UUID userId) {
        // 1. 入参约定非 null；去重后为空表示无图可验，直接放行
        Objects.requireNonNull(fileIds, "fileIds 不能为 null");
        Set<UUID> distinctIds = new LinkedHashSet<>(fileIds);
        if (distinctIds.isEmpty()) {
            return;
        }
        // 2. 一条查询验完归属和 MIME，去重计数避免重复 ID 误判
        List<MediaFile> found = mediaFileMapper.selectOwnedStorageKeys(userId, distinctIds, ALLOWED_IMAGE_TYPES);
        // 3. 数量对不上就是有坏图，把坏 ID 记下来方便排查
        if (found.size() != distinctIds.size()) {
            Set<UUID> missing = new LinkedHashSet<>(distinctIds);
            found.forEach(file -> missing.remove(file.getId()));
            log.warn("图片校验失败，userId={}，无效图片={}", userId, missing);
            throw BusinessException.badRequest("FILE_NOT_USABLE", "请选择已上传完成的本人图片");
        }
    }

    /**
     * 按文件 ID 签 10 分钟读取地址，不校验归属——读取资格由调用方的业务关联背书。
     * 文件失效属于"关联指向了已消失文件"的脏数据，记 ERROR 后返 null，不炸调用方。
     *
     * <p>流程：查文件对象键 → 本地签名读取地址。只读查询，无副作用。</p>
     *
     * @param fileId 文件 ID，非 null
     * @return 预签名 GET 地址；文件未登记或已删除时为 null
     */
    public String getReadUrl(UUID fileId) {
        // 1. 入参约定非 null
        Objects.requireNonNull(fileId, "fileId 必须非空");
        // 2. 查对象键；查不到就是关联指向了失效文件，大声记 ERROR 后返 null
        MediaFile file = mediaFileMapper.selectById(fileId);
        if (file == null) {
            log.error("文件失效，读取地址未生成，fileId={}", fileId);
            return null;
        }
        // 3. 本地签名读取地址
        return createReadUrl(file.getStorageKey());
    }

    /**
     * 批量签 10 分钟读取地址，不校验归属——读取资格由调用方的业务关联背书。
     * 失效文件记 ERROR 后省略该项，不炸整页。
     *
     * <p>流程：空集合直接返回 → 批量查对象键并签名 → 缺失的记 ERROR。只读查询，无副作用。</p>
     *
     * @param fileIds 已授权展示的文件 ID 集合，允许为空
     * @return 有效文件 ID 到签名 URL 的映射，失效项不出现；空入参返回空映射
     */
    public Map<UUID, String> getReadUrls(Collection<UUID> fileIds) {
        // 1. 空集合直接返回
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        // 2. 批量查对象键并签名
        Map<UUID, String> urls = mediaFileMapper.selectStorageKeys(fileIds).stream()
                .collect(Collectors.toMap(MediaFile::getId, file -> createReadUrl(file.getStorageKey())));
        // 3. 缺失的说明关联指向了失效文件，大声记 ERROR 后省略该项
        Set<UUID> missing = new LinkedHashSet<>(fileIds);
        missing.removeAll(urls.keySet());
        if (!missing.isEmpty()) {
            log.error("文件失效，读取地址未生成，fileIds={}", missing);
        }
        return urls;
    }

    /**
     * 生成图片直传地址：服务端先定好文件 ID 和对象键，签一个 10 分钟有效的预签名 PUT 地址。
     * 此时只登记意图、不写库，文件入库要等上传完成后的事件登记（handleUploadEvent）。
     *
     * <p>流程：生成 UUIDv7 文件 ID → 拼对象键 images/{userId}/{fileId} → 签 PUT 预签名地址。
     * 副作用：只打日志，不写库。</p>
     *
     * @param request 已由 MVC 校验的上传申请；mimeType 由请求枚举限定，byteSize 是声明值
     * @param userId  当前登录用户 ID
     * @return 文件 ID 和 10 分钟有效的 PUT 上传地址
     */
    public CreateUploadResponse createUpload(CreateUploadRequest request, UUID userId) {
        // 1. 先定文件身份：UUIDv7 文件 ID，对象键按约定拼好
        UUID fileUuid = UuidCreator.getTimeOrderedEpoch();
        String storageKey = STORAGE_KEY_PREFIX + userId + "/" + fileUuid;
        // 2. 签 PUT 预签名地址，Content-Type 固定成申请时的图片类型
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .contentType(request.getMimeType().toString())
                .build();
        PresignedPutObjectRequest signedRequest = presigner.presignPutObject(
                builder -> builder
                        .signatureDuration(UPLOAD_URL_VALIDITY)
                        .putObjectRequest(putRequest));
        // 3. 打日志便于对账，返回文件 ID 和上传地址
        log.info("上传地址已生成，userId={}，fileId={}，storageKey={}，mimeType={}，declaredByteSize={}",
                userId, fileUuid, storageKey, request.getMimeType(), request.getByteSize());
        return new CreateUploadResponse(fileUuid, signedRequest.url().toString());
    }

    /**
     * 查图片是否已登记入库、可供当前用户关联使用：存在、未删除、本人、图片类型四项都过才算就绪。
     *
     * <p>流程：按四项口径查存在性。只读查询，无副作用。</p>
     *
     * @param fileId 文件 ID
     * @param userId 当前用户 ID
     * @return 就绪状态，fileId 回显便于客户端对账
     */
    public UploadStatusResponse getUploadStatus(UUID fileId, UUID userId) {
        // 四项口径和写入校验一致，避免"查得到却用不了"的口径分裂
        boolean ready = mediaFileMapper.existsOwnedFile(fileId, userId, ALLOWED_IMAGE_TYPES);
        log.debug("查询上传就绪状态，userId={}，fileId={}，ready={}", userId, fileId, ready);
        return new UploadStatusResponse(fileId, ready);
    }

    /**
     * 处理对象存储推来的上传事件：过滤出本桶的 Put 事件，逐条登记文件元数据。
     *
     * <p>流程：逐条判事件类型和桶 → 对象键 URL 解码 → 登记（registerFile）。
     * 副作用：登记成功往 media_file 插记录，跟随本方法事务；无关事件只打日志。
     * 登记失败异常上抛，让消息重投重试。</p>
     *
     * @param request 对象存储推送的上传事件批次
     */
    @Transactional
    public void handleUploadEvent(MediaUploadEvent request) {
        // 1. 逐条看事件，只认本桶的 Put 上传，其余记日志忽略
        log.info("收到文件上传事件，recordCount={}", request.records().size());
        for (MediaUploadEvent.EventRecord event : request.records()) {
            boolean isUpload = "s3:ObjectCreated:Put".equals(event.eventName())
                    || "ObjectCreated:Put".equals(event.eventName());

            if (isUpload && bucket.equals(event.s3().bucket().name())) {
                // 2. 对象键要 URL 解码后才能用
                String storageKey = URLDecoder.decode(
                        event.s3().object().key(),
                        StandardCharsets.UTF_8);

                // 3. 登记入库，失败抛出去让消息重投
                registerFile(storageKey);
            } else {
                log.info("忽略无关存储事件，eventName={}，bucket={}",
                        event.eventName(), event.s3().bucket().name());
            }
        }
    }

    // =====================================================================
    // 以上：user / moment / album 与 media 入口使用的公开方法
    // ---------------------------------------------------------------------
    // 以下：内部辅助方法
    // =====================================================================

    /**
     * 按对象键登记一条文件记录：解析键、核对对象存储侧元数据、过大小和类型检查后入库。
     * 已登记过的（重复投递）直接跳过，保持原记录不动。
     *
     * <p>流程：解析对象键 → 查对象存储元数据 → 大小/类型检查 → 插 media_file。
     * 副作用：往 media_file 插一行，跟随外层事务；并发重复登记由唯一约束兜底。</p>
     *
     * @param storageKey 已 URL 解码的对象键，约定 images/{userId}/{fileId}
     * @throws SdkException 查对象存储元数据失败时上抛，让消息重投重试
     */
    private void registerFile(String storageKey) {
        // 1. 对象键约定 images/{userId}/{fileId}，对不上就不当业务文件，记日志跳过
        String[] parts = storageKey.split("/", -1);

        if (parts.length != 3 || !"images".equals(parts[0])) {
            log.warn("忽略非业务文件事件，storageKey={}", storageKey);
            return;
        }

        UUID userId;
        UUID fileId;
        try {
            userId = UUID.fromString(parts[1]);
            fileId = UUID.fromString(parts[2]);
        } catch (IllegalArgumentException exception) {
            // ID 段不是合法 UUID（比如人工传的杂项）同样跳过；抛出去会让消息无限重投
            log.warn("忽略非业务文件事件，storageKey={}，reason=ID 段不是合法 UUID", storageKey);
            return;
        }

        // 2. 重复投递时复用已有记录
        if (mediaFileMapper.selectById(fileId) != null) {
            log.debug("文件记录已存在，忽略重复登记，fileId={}", fileId);
            return;
        }

        // 3. 查对象存储拿真实大小和类型，查询失败上抛让消息重投
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

        // 4. 大小和类型复核：申请时的声明不算数，以对象存储里的真实元数据为准；不合格只记日志不入库
        long byteSize = metadata.contentLength();
        String mimeType = metadata.contentType();

        if (byteSize <= 0 || byteSize > MAX_UPLOAD_BYTES
                || !ALLOWED_IMAGE_TYPES.contains(mimeType)) {

            log.warn("文件未通过上传检查，fileId={}，byteSize={}，mimeType={}",
                    fileId, byteSize, mimeType);
            return;
        }

        // 5. 入库，审计时间用本次统一时间
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
            // 此处记录事务内的写入步骤，最终提交由 handleUploadEvent 的事务完成
            log.info("写入文件元数据，userId={}，fileId={}，byteSize={}，mimeType={}",
                    userId, fileId, byteSize, mimeType);
        } catch (DuplicateKeyException exception) {
            // 并发重复通知由数据库唯一约束兜底，保留已有记录
            log.debug("文件记录已存在，忽略重复登记，fileId={}", fileId);
        }
    }

    // =====================================================================
    // 以上：内部辅助方法
    // ---------------------------------------------------------------------
    // 以下：底层签名方法（getReadUrl(s) 的签名底层；circle 默认头像改造后收成私有）
    // =====================================================================

    /**
     * 按对象键生成 10 分钟读取地址，不查库、不校验资格，读取权限由调用方确认。
     *
     * <p>纯签名计算，无副作用。</p>
     *
     * @param storageKey 文件在对象存储中的完整键，例如 images/{userId}/{fileId}
     * @return 预签名 GET 地址
     */
    public String createReadUrl(String storageKey) {
        // 拿对象键直接签名，键的合法性由调用方保证
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
}
