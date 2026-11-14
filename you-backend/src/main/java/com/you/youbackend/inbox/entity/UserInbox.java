package com.you.youbackend.inbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.you.youbackend.inbox.model.InboxObjectType;
import com.you.youbackend.inbox.model.InboxSourceType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** 接收者的一条分享动态，只记录内容及来源引用；图文继续存放在原业务表中。 */
@Getter
@Setter
@TableName("user_inbox")
public class UserInbox {
    /** 当前接收者的动态 ID，服务端生成 UUIDv7。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 信箱记录创建时间，UTC 毫秒精度。 */
    private LocalDateTime createdAt;
    /** 信箱记录修改时间，UTC 毫秒精度。 */
    private LocalDateTime updatedAt;
    /** 接收者 ID，包含本次分享者本人。 */
    private UUID recipientUserId;
    /** 本次关联瞬间或分享内容的用户 ID。 */
    private UUID actorUserId;
    /** 来源空间类型，使用枚举名称保存。 */
    private InboxSourceType sourceType;
    /** 来源同行或圈子的 ID。 */
    private UUID sourceId;
    /** 分享内容类型，使用枚举名称保存。 */
    private InboxObjectType objectType;
    /** 原瞬间或时光的 ID。 */
    private UUID objectId;
    /** 关联或分享实际发生时间，UTC 毫秒精度，作为动态流排序依据。 */
    private LocalDateTime sharedAt;
    /** 圈子分享关系 ID；同行动态为 null。 */
    private UUID shareId;
    /** 已读时间，UTC；本批只投递，保持 null。 */
    private LocalDateTime readAt;
    /** 动态失效时间，UTC；null 表示记录有效，沿用全局逻辑删除配置。 */
    private LocalDateTime deletedAt;
}
