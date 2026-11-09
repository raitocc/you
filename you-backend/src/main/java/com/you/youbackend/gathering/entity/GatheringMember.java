package com.you.youbackend.gathering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** 同行成员关系，同一同行与用户由数据库唯一索引约束。 */
@Getter
@Setter
@TableName("gathering_member")
public class GatheringMember {
    /** 关系 ID，服务端生成的 UUIDv7。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 创建时间，UTC 毫秒精度。 */
    private LocalDateTime createdAt;
    /** 最后修改时间，UTC 毫秒精度。 */
    private LocalDateTime updatedAt;
    /** 同行 ID，关联 gathering.id。 */
    private UUID gatheringId;
    /** 成员 ID，关联 app_user.id。 */
    private UUID userId;
    /** 离开或移除时间，UTC；null 表示有效成员。 */
    private LocalDateTime deletedAt;
}