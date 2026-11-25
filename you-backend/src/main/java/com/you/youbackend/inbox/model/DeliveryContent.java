package com.you.youbackend.inbox.model;

import com.you.youbackend.common.enumeration.ObjectType;
import com.you.youbackend.common.enumeration.SourceType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;


@Schema(description = "信箱 user_inbox 里一条分享所有接收者共用的 DTO 数据对象")
public record DeliveryContent(

        @Schema(description = "来源的空间类型，同行或圈子：【枚举类型】")
        SourceType sourceType,

        @Schema(description = "来源同行或圈子的 ID")
        UUID sourceId,

        @Schema(description = "本次分享瞬间或时光关联的用户 ID")
        UUID actorUserId,

        @Schema(description = "分享内容类型，瞬间或时光：【枚举类型】")
        ObjectType objectType,

        @Schema(description = "原瞬间或时光的 ID")
        UUID objectId,

        @Schema(description = "circle_share ID：【同行类型该字段为 null；查询时用于确认该条分享是否有效】")
        UUID shareId,

        @Schema(description = "分享实际发生时间：【UTC 毫秒精度，作为分页排序依据】")
        Instant sharedAt
) {
}
