package com.you.youbackend.album.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** 时光与瞬间的关联，同一时光内的瞬间由唯一索引约束。 */
@Getter
@Setter
@TableName("album_moment")
public class AlbumMoment {
    /** 关联 ID，服务端生成的 UUIDv7。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 时光 ID。 */
    private UUID albumId;
    /** 瞬间 ID。 */
    private UUID momentId;
    /** 创建时间，UTC。 */
    private LocalDateTime createdAt;
    /** 最后修改时间，UTC。 */
    private LocalDateTime updatedAt;
    /** 移出时间，UTC；null 表示关联有效。 */
    private LocalDateTime deletedAt;
}
