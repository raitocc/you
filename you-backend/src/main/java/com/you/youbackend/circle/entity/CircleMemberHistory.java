package com.you.youbackend.circle.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;

/** 成员历史读写投影，显式映射离开时间以访问软删除记录。 */
@Getter
@Setter
@TableName("circle_member")
public class CircleMemberHistory {
    /** 原成员关系 ID。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 圈子 ID。 */
    private UUID circleId;
    /** 成员用户 ID。 */
    private UUID userId;
    /** 是否限制再次加入。 */
    private Boolean joinRestricted;
    /** 离开或移除时间，null 表示成员有效。 */
    @TableField("deleted_at")
    private LocalDateTime leftAt;
    /** 关系最近修改时间，UTC。 */
    private LocalDateTime updatedAt;
}
