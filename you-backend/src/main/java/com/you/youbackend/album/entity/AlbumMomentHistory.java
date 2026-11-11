package com.you.youbackend.album.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;

/** 时光瞬间关联历史，包含已移出的记录。 */
@Getter
@Setter
@TableName("album_moment")
public class AlbumMomentHistory {
    /** 原记录 ID。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 时光 ID。 */
    private UUID albumId;
    /** 瞬间 ID。 */
    private UUID momentId;
    /** 关联创建时间，UTC。 */
    private LocalDateTime createdAt;
    /** 关联修改时间，UTC。 */
    private LocalDateTime updatedAt;
    /** 删除时间，UTC；显式映射以读取已软删除记录。 */
    @TableField("deleted_at")
    private LocalDateTime removedAt;
}
