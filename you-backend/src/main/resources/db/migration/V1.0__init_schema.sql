-- YOU 初始表结构：14 张业务表。
-- UUIDv7 由应用生成，按原始字节顺序存入 BINARY(16)。
-- created_at、updated_at 由应用按 UTC 填写，deleted_at 为空表示有效。
-- 对象关联、跨表业务规则及图片顺序由应用层维护。

-- 1. 用户
CREATE TABLE `app_user` (
    `id`             BINARY(16)   NOT NULL COMMENT '用户 ID，UUIDv7',
    `username`       VARCHAR(64)  NOT NULL COMMENT '登录账号，唯一，比较时忽略大小写',
    `password_hash`  VARCHAR(255) NOT NULL COMMENT '密码散列',
    `nickname`       VARCHAR(64)  NULL COMMENT '展示昵称，允许重名',
    `avatar_file_id` BINARY(16)   NULL COMMENT '头像文件 ID，关联 media_file.id',
    `created_at`     DATETIME(3)  NOT NULL COMMENT '创建时间，UTC',
    `updated_at`     DATETIME(3)  NOT NULL COMMENT '最后修改时间，UTC',
    `deleted_at`     DATETIME(3)  NULL COMMENT '删除时间，UTC；为空表示未删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';

-- 2. 媒体文件
CREATE TABLE `media_file` (
    `id`            BINARY(16)   NOT NULL COMMENT '文件 ID，UUIDv7',
    `owner_user_id` BINARY(16)   NOT NULL COMMENT '上传者 ID，关联 app_user.id',
    `storage_key`   VARCHAR(512) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'RustFS 对象键，唯一，按大小写精确匹配',
    `mime_type`     VARCHAR(64)  NOT NULL COMMENT '实际文件类型，例如 image/jpeg',
    `byte_size`     BIGINT       NOT NULL COMMENT '文件字节数，正整数',
    `created_at`    DATETIME(3)  NOT NULL COMMENT '创建时间，UTC',
    `updated_at`    DATETIME(3)  NOT NULL COMMENT '最后修改时间，UTC',
    `deleted_at`    DATETIME(3)  NULL COMMENT '删除时间，UTC；为空表示未删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_media_file_storage_key` (`storage_key`),
    KEY `idx_media_file_owner` (`owner_user_id`, `deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='媒体文件';

-- 3. 瞬间
CREATE TABLE `moment` (
    `id`               BINARY(16)    NOT NULL COMMENT '瞬间 ID，UUIDv7',
    `created_at`       DATETIME(3)   NOT NULL COMMENT '创建时间，即发布时间，UTC',
    `updated_at`       DATETIME(3)   NOT NULL COMMENT '最后修改时间，UTC',
    `author_user_id`   BINARY(16)    NOT NULL COMMENT '作者 ID，关联 app_user.id',
    `gathering_id`     BINARY(16)    NULL COMMENT '当前同行 ID，关联 gathering.id',
    `title`            VARCHAR(255)  NULL COMMENT '标题',
    `body`             TEXT          NULL COMMENT '普通文本正文；有效正文和图片至少有一种',
    `mood`             VARCHAR(64)   NULL COMMENT '心情',
    `memory_time`      DATETIME(3)   NOT NULL COMMENT '经历发生时间，UTC',
    `latitude`         DECIMAL(10,7) NULL COMMENT '纬度，-90 至 90，与经度同时有值或同时为空',
    `longitude`        DECIMAL(10,7) NULL COMMENT '经度，-180 至 180，与纬度同时有值或同时为空',
    `location_name`    VARCHAR(255)  NULL COMMENT '地点名称',
    `location_address` VARCHAR(512)  NULL COMMENT '地点地址',
    `deleted_at`       DATETIME(3)   NULL COMMENT '删除时间，UTC；为空表示未删除',
    PRIMARY KEY (`id`),
    KEY `idx_moment_author_feed` (`author_user_id`, `deleted_at`, `memory_time`, `id`),
    KEY `idx_moment_gathering_feed` (`gathering_id`, `deleted_at`, `memory_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='瞬间';

-- 4. 瞬间图片：同一瞬间与文件保留一条关系，重新添加时恢复原行。
CREATE TABLE `moment_image` (
    `id`         BINARY(16)  NOT NULL COMMENT '图片关联 ID，UUIDv7',
    `moment_id`  BINARY(16)  NOT NULL COMMENT '瞬间 ID，关联 moment.id',
    `file_id`    BINARY(16)  NOT NULL COMMENT '图片文件 ID，关联 media_file.id',
    `sort_order` INT         NOT NULL COMMENT '展示顺序，从 1 开始，有效图片的顺序由应用维护',
    `created_at` DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    `updated_at` DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    `deleted_at` DATETIME(3) NULL COMMENT '移除时间，UTC；为空表示图片关联有效',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_moment_image_moment_file` (`moment_id`, `file_id`),
    KEY `idx_moment_image_order` (`moment_id`, `deleted_at`, `sort_order`),
    KEY `idx_moment_image_file` (`file_id`, `deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='瞬间图片';

-- 5. 瞬间标签
CREATE TABLE `moment_tag` (
    `id`         BINARY(16)  NOT NULL COMMENT '标签记录 ID，UUIDv7',
    `moment_id`  BINARY(16)  NOT NULL COMMENT '瞬间 ID，关联 moment.id',
    `tag_name`   VARCHAR(64) NOT NULL COMMENT '标签名称，非空白，同一瞬间内唯一',
    `created_at` DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    `updated_at` DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    `deleted_at` DATETIME(3) NULL COMMENT '移除时间，UTC；为空表示标签有效',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_moment_tag_moment_name` (`moment_id`, `tag_name`),
    KEY `idx_moment_tag_name` (`tag_name`, `deleted_at`, `moment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='瞬间标签';

-- 6. 时光
CREATE TABLE `album` (
    `id`              BINARY(16)   NOT NULL COMMENT '时光 ID，UUIDv7',
    `created_at`      DATETIME(3)  NOT NULL COMMENT '创建时间，UTC',
    `updated_at`      DATETIME(3)  NOT NULL COMMENT '最后修改时间，UTC',
    `owner_user_id`   BINARY(16)   NOT NULL COMMENT '所有者 ID，关联 app_user.id',
    `name`            VARCHAR(128) NOT NULL COMMENT '时光名称，非空白，允许重名',
    `description`     TEXT         NULL COMMENT '时光介绍',
    `organize_mode`   VARCHAR(16)  NOT NULL DEFAULT 'manual' COMMENT '整理方式：manual 手动，ai AI 整理',
    `organize_prompt` TEXT         NULL COMMENT '本次 AI 整理要求',
    `cover_file_id`   BINARY(16)   NULL COMMENT '封面文件 ID，关联 media_file.id',
    `deleted_at`      DATETIME(3)  NULL COMMENT '删除时间，UTC；为空表示未删除',
    PRIMARY KEY (`id`),
    KEY `idx_album_owner_list` (`owner_user_id`, `deleted_at`, `created_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='时光';

-- 7. 时光瞬间关联
CREATE TABLE `album_moment` (
    `id`         BINARY(16)  NOT NULL COMMENT '关联 ID，UUIDv7',
    `created_at` DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    `updated_at` DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    `album_id`   BINARY(16)  NOT NULL COMMENT '时光 ID，关联 album.id',
    `moment_id`  BINARY(16)  NOT NULL COMMENT '瞬间 ID，关联 moment.id',
    `deleted_at` DATETIME(3) NULL COMMENT '移出时间，UTC；为空表示关联有效',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_album_moment_album_moment` (`album_id`, `moment_id`),
    KEY `idx_album_moment_moment` (`moment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='时光瞬间关联';

-- 8. 同行
CREATE TABLE `gathering` (
    `id`              BINARY(16)   NOT NULL COMMENT '同行 ID，UUIDv7',
    `created_at`      DATETIME(3)  NOT NULL COMMENT '创建时间，UTC',
    `updated_at`      DATETIME(3)  NOT NULL COMMENT '最后修改时间，UTC',
    `creator_user_id` BINARY(16)   NOT NULL COMMENT '创建者 ID，关联 app_user.id，负责同行管理',
    `name`            VARCHAR(128) NOT NULL COMMENT '同行名称，非空白，允许重名',
    `description`     TEXT         NULL COMMENT '同行介绍',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '同行状态：1 进行中，2 已结束',
    `invite_enabled`  TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许通过同行码加入：0 否，1 是',
    `invite_code`     VARCHAR(16)  NOT NULL COMMENT '同行邀请码，唯一，由系统随机生成，可重置',
    `deleted_at`      DATETIME(3)  NULL COMMENT '解散时间，UTC；为空表示未解散',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_gathering_invite_code` (`invite_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同行';

-- 9. 同行成员
CREATE TABLE `gathering_member` (
    `id`           BINARY(16)  NOT NULL COMMENT '成员关系 ID，UUIDv7',
    `created_at`   DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    `updated_at`   DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    `gathering_id` BINARY(16)  NOT NULL COMMENT '同行 ID，关联 gathering.id',
    `user_id`      BINARY(16)  NOT NULL COMMENT '成员 ID，关联 app_user.id',
    `deleted_at`   DATETIME(3) NULL COMMENT '离开或移除时间，UTC；为空表示成员关系有效',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_gathering_member_gathering_user` (`gathering_id`, `user_id`),
    KEY `idx_gathering_member_user` (`user_id`, `deleted_at`, `gathering_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同行成员';

-- 10. 圈子
CREATE TABLE `circle` (
    `id`              BINARY(16)   NOT NULL COMMENT '圈子 ID，UUIDv7',
    `created_at`      DATETIME(3)  NOT NULL COMMENT '创建时间，UTC',
    `updated_at`      DATETIME(3)  NOT NULL COMMENT '最后修改时间，UTC',
    `creator_user_id` BINARY(16)   NOT NULL COMMENT '创建者 ID，关联 app_user.id，负责圈子管理',
    `name`            VARCHAR(128) NOT NULL COMMENT '圈子名称，非空白，允许重名',
    `description`     TEXT         NULL COMMENT '圈子介绍',
    `invite_enabled`  TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许通过圈子码加入：0 否，1 是',
    `invite_code`     VARCHAR(16)  NOT NULL COMMENT '圈子邀请码，唯一，由系统随机生成，可重置',
    `deleted_at`      DATETIME(3)  NULL COMMENT '解散时间，UTC；为空表示未解散',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_circle_invite_code` (`invite_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='圈子';

-- 11. 圈子成员
CREATE TABLE `circle_member` (
    `id`         BINARY(16)  NOT NULL COMMENT '成员关系 ID，UUIDv7',
    `created_at` DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    `updated_at` DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    `circle_id`  BINARY(16)  NOT NULL COMMENT '圈子 ID，关联 circle.id',
    `user_id`    BINARY(16)  NOT NULL COMMENT '成员 ID，关联 app_user.id',
    `deleted_at` DATETIME(3) NULL COMMENT '离开或移除时间，UTC；为空表示成员关系有效',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_circle_member_circle_user` (`circle_id`, `user_id`),
    KEY `idx_circle_member_user` (`user_id`, `deleted_at`, `circle_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='圈子成员';

-- 12. 圈子分享
CREATE TABLE `circle_share` (
    `id`             BINARY(16)  NOT NULL COMMENT '分享关系 ID，UUIDv7',
    `created_at`     DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    `updated_at`     DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    `circle_id`      BINARY(16)  NOT NULL COMMENT '目标圈子 ID，关联 circle.id',
    `object_type`    VARCHAR(16) NOT NULL COMMENT '对象类型：moment 瞬间，album 时光，gathering 同行',
    `object_id`      BINARY(16)  NOT NULL COMMENT '原对象 ID，由 object_type 确定对应表',
    `sharer_user_id` BINARY(16)  NOT NULL COMMENT '本次分享者 ID，关联 app_user.id',
    `shared_at`      DATETIME(3) NOT NULL COMMENT '本次分享时间，UTC；重新分享时更新',
    `deleted_at`     DATETIME(3) NULL COMMENT '撤回或移除时间，UTC；为空表示分享关系有效',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_circle_share_circle_object` (`circle_id`, `object_type`, `object_id`),
    KEY `idx_circle_share_feed` (`circle_id`, `deleted_at`, `shared_at`, `id`),
    KEY `idx_circle_share_object` (`object_type`, `object_id`, `deleted_at`, `circle_id`),
    KEY `idx_circle_share_sharer` (`circle_id`, `sharer_user_id`, `deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='圈子分享';

-- 13. 用户信箱
CREATE TABLE `user_inbox` (
    `id`                BINARY(16)  NOT NULL COMMENT '信箱动态 ID，UUIDv7',
    `created_at`        DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    `updated_at`        DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    `recipient_user_id` BINARY(16)  NOT NULL COMMENT '接收者 ID，关联 app_user.id',
    `actor_user_id`     BINARY(16)  NOT NULL COMMENT '发布或分享者 ID，关联 app_user.id',
    `source_type`       VARCHAR(16) NOT NULL COMMENT '来源空间类型：gathering 同行，circle 圈子',
    `source_id`         BINARY(16)  NOT NULL COMMENT '来源同行或圈子的 ID',
    `object_type`       VARCHAR(16) NOT NULL COMMENT '内容类型：moment 瞬间，album 时光，gathering 同行',
    `object_id`         BINARY(16)  NOT NULL COMMENT '对应内容 ID',
    `read_at`           DATETIME(3) NULL COMMENT '已读时间，UTC；为空表示未读',
    `deleted_at`        DATETIME(3) NULL COMMENT '删除时间，UTC；为空表示未删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_inbox_recipient_feed` (`recipient_user_id`, `deleted_at`, `created_at`, `id`),
    KEY `idx_user_inbox_unread` (`recipient_user_id`, `deleted_at`, `read_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户信箱';

-- 14. 站内通知
CREATE TABLE `notification` (
    `id`                BINARY(16)   NOT NULL COMMENT '通知 ID，UUIDv7',
    `created_at`        DATETIME(3)  NOT NULL COMMENT '创建时间，UTC',
    `updated_at`        DATETIME(3)  NOT NULL COMMENT '最后修改时间，UTC',
    `recipient_user_id` BINARY(16)   NOT NULL COMMENT '接收者 ID，关联 app_user.id',
    `actor_user_id`     BINARY(16)   NULL COMMENT '触发者 ID，关联 app_user.id；系统通知可空',
    `notification_type` VARCHAR(32)  NOT NULL COMMENT '通知类型，如 member_added、removed、dissolved、ai_completed；like、comment 预留',
    `target_type`       VARCHAR(16)  NULL COMMENT '关联对象类型：moment、album、gathering、circle，与 target_id 同时为空或有值',
    `target_id`         BINARY(16)   NULL COMMENT '关联对象 ID，与 target_type 同时为空或有值',
    `content`           VARCHAR(255) NOT NULL COMMENT '简短通知文案',
    `read_at`           DATETIME(3)  NULL COMMENT '已读时间，UTC；为空表示未读',
    `deleted_at`        DATETIME(3)  NULL COMMENT '删除时间，UTC；为空表示未删除',
    PRIMARY KEY (`id`),
    KEY `idx_notification_recipient_feed` (`recipient_user_id`, `deleted_at`, `created_at`, `id`),
    KEY `idx_notification_unread` (`recipient_user_id`, `deleted_at`, `read_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内通知';
