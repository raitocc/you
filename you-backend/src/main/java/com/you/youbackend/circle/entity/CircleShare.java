package com.you.youbackend.circle.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;
import com.you.youbackend.circle.model.CircleShareObjectType;
/** 一条圈子分享记录，保存分享人、目标圈子和原内容的 ID。 */
@Getter @Setter @TableName("circle_share")
public class CircleShare {
    /** 这条分享的 ID，使用 UUIDv7。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 接收这条分享的圈子 ID。 */
    private UUID circleId;
    /** 原内容的类型，瞬间或时光。 */
    private CircleShareObjectType objectType;
    /** 被分享的瞬间 ID 或时光 ID。 */
    private UUID objectId;
    /** 分享者 ID。 */
    private UUID sharerUserId;
    /** 最近一次新建或恢复分享的时间，使用 UTC。 */
    private LocalDateTime sharedAt;
    /** 第一次分享的时间，使用 UTC。 */
    private LocalDateTime createdAt;
    /** 分享记录最后更新的时间，使用 UTC。 */
    private LocalDateTime updatedAt;
    /** 撤回或失效的时间，使用 UTC。null 表示这条记录尚未标记撤回或失效。 */
    private LocalDateTime deletedAt;
}
