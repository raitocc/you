package com.you.youbackend.circle.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;
/** circle 持久化记录，软删除沿用全局配置。 */
@Getter
@Setter
@TableName("circle")
public class Circle {
    /** UUIDv7 标识。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 创建者 ID。 */
    private UUID creatorUserId;
    /** 去首尾空白后的名称。 */
    private String name;
    /** 介绍，null 表示未设置。 */
    private String description;
    /** 自定义头像，null 使用默认头像。 */
    private UUID avatarFileId;
    /** 邀请开关。 */
    private Boolean inviteEnabled;
    /** 从 10000 开始分配的自增数字邀请码。 */
    private Long inviteCode;
    /** 创建时间，UTC 毫秒精度。 */
    private LocalDateTime createdAt;
    /** 修改时间，UTC 毫秒精度。 */
    private LocalDateTime updatedAt;
    /** 删除时间，null 表示有效。 */
    private LocalDateTime deletedAt;
}
