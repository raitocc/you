package com.you.youbackend.moment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * TODO：补充实体类及字段的中文注释，说明图片关联、展示顺序和软删除含义。
 */
@Getter
@Setter
@TableName("moment_image")
public class MomentImage {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID momentId;
    private UUID fileId;
    private int sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
