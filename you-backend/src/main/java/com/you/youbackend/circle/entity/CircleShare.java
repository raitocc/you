package com.you.youbackend.circle.entity;
import com.baomidou.mybatisplus.annotation.*;
import com.you.youbackend.common.enumeration.ObjectType;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;
/** 一条圈子分享记录，保存分享人、目标圈子和原内容的 ID。 */
@Getter
@Setter
@TableName("circle_share")
public class CircleShare {
    /** 这条分享的 ID，使用 UUIDv7。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 接收这条分享的圈子 ID。 */
    private UUID circleId;
    /** 原内容的类型，瞬间或时光。 */
    private ObjectType objectType;
    /** 被分享的瞬间 ID 或时光 ID。 */
    private UUID objectId;
    /** 分享者 ID。 */
    private UUID sharerUserId;
    /** 最近一次新建或恢复分享的时间，使用 UTC。 */
    private Instant sharedAt;
    /** 第一次分享的时间，使用 UTC。 */
    private Instant createdAt;
    /** 分享记录最后更新的时间，使用 UTC。 */
    private Instant updatedAt;
    /** 撤回或失效的时间，使用 UTC。null 表示这条记录尚未标记撤回或失效。 */
    @TableField("deleted_at")
    private Instant withdrawnAt;

    public Instant getDeletedAt() {
        return withdrawnAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.withdrawnAt = deletedAt;
    }
}
