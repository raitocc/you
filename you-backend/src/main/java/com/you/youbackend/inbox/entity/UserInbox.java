package com.you.youbackend.inbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.you.youbackend.common.enumeration.ObjectType;
import com.you.youbackend.common.enumeration.SourceType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class UserInbox {
    // 主键 id：【UUIDv7，由服务端生成】
    @TableId(type = IdType.INPUT)
    private UUID id;

    // 信箱记录创建时间：【UTC 毫秒精度】
    private Instant createdAt;

    // 信箱记录修改时间：【UTC 毫秒精度】
    private Instant updatedAt;

    // 接收者 ID：【包含分享者本人】
    private UUID recipientUserId;

    // 本次分享瞬间或时光关联的用户 ID
    private UUID actorUserId;

    // 来源的空间类型，同行或圈子：【枚举类型】
    private SourceType sourceType;

    // 来源同行或圈子的 ID
    private UUID sourceId;

    // 分享内容类型，瞬间或时光：【枚举类型】
    private ObjectType objectType;

    // 原瞬间或时光的 ID
    private UUID objectId;

    // 分享实际发生时间：【UTC 毫秒精度，作为分页排序依据】
    private Instant sharedAt;

    // circle_share ID：【同行类型该字段为 null；查询时用于确认该条分享是否有效】
    private UUID shareId;

    // 分享已读时间：【UTC；本批只投递，保持 null；TODO：给以后做未读数或已读状态留位置。】
    private Instant readAt;

    // 分享失效时间：【UTC；null 表示记录有效，沿用全局逻辑删除配置】
    private Instant deletedAt;



}
