-- 上传凭据与幂等确认结果；确认成功后保留 24 小时。
CREATE TABLE media_upload (
 id BINARY(16) NOT NULL,
 owner_user_id BINARY(16) NOT NULL,
 storage_key VARCHAR(512) COLLATE utf8mb4_0900_bin NOT NULL,
 mime_type VARCHAR(64) NOT NULL,
 byte_size BIGINT NOT NULL,
 expires_at DATETIME(3) NOT NULL,
 retain_until DATETIME(3) NOT NULL,
 file_id BINARY(16) NULL,
 completed_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL,
 PRIMARY KEY (id),
 KEY idx_media_upload_retention (retain_until),
 KEY idx_media_upload_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上传凭据及确认结果';
