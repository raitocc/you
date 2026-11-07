package com.you.youbackend.moment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 瞬间与媒体文件的关联记录，维护图片展示顺序。
 * 同一瞬间、文件组合保留一条记录；移除关联后原媒体文件仍然保留。
 */
@Getter
@Setter
@TableName("moment_image")
public class MomentImage {

    /** 图片关联 ID，服务端生成的 UUIDv7，与媒体文件 ID 区分。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 所属瞬间 ID，关联 moment.id。 */
    private UUID momentId;

    /** 媒体文件 ID，关联 media_file.id；对象存储键及文件元数据由媒体模块维护。 */
    private UUID fileId;

    /** 图片展示顺序，从 1 开始，按用户提交的图片排列维护。 */
    private int sortOrder;

    /** 关联首次创建时间，UTC。 */
    private LocalDateTime createdAt;

    /** 关联最后修改时间，UTC。 */
    private LocalDateTime updatedAt;

    /** 关联移除时间，UTC；null 表示有效，重新添加时恢复原关联。 */
    private LocalDateTime deletedAt;
}
