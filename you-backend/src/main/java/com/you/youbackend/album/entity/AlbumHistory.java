package com.you.youbackend.album.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;

/** 时光删除校验所需的归属及删除状态。 */
@Getter
@Setter
@TableName("album")
public class AlbumHistory {
    /** 原记录 ID。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 时光所有者 ID。 */
    private UUID ownerUserId;
    /** 删除时间，UTC；显式映射以读取已软删除记录。 */
    @TableField("deleted_at")
    private LocalDateTime removedAt;
}
