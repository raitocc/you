package com.you.youbackend.media.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 媒体文件元数据，文件内容存放在对象存储中。
 */
@Getter
@Setter
@TableName("media_file")
public class MediaFile {

    /** 文件 ID，由应用生成 UUIDv7。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 上传者 ID。 */
    private UUID ownerUserId;

    /** 对象存储中的完整键，例如 images/文件UUID。 */
    private String storageKey;

    /** 媒体类型，例如 image/jpeg。 */
    private String mimeType;

    /** 文件字节数。 */
    private Long byteSize;

    /** 创建时间，UTC。 */
    private LocalDateTime createdAt;

    /** 最后修改时间，UTC。 */
    private LocalDateTime updatedAt;

    /** 删除时间，UTC；null 表示有效。 */
    private LocalDateTime deletedAt;
}