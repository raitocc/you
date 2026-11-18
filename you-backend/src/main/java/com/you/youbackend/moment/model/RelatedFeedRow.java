package com.you.youbackend.moment.model;

import com.you.youbackend.common.enumeration.ObjectType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Schema(description = "与你有关的一条 user_inbox 数据查询对象")
public class RelatedFeedRow {
        /**
         * 信箱 ID：【user_inbox 的 id】
         */
        @Schema(description = "信箱 ID：【user_inbox 的 id】",format = "uuid")
        UUID id;

        /**
         * 信箱分享时间：【user_inbox 的 share_at 】
         */
        @Schema(description = "信箱分享时间：【user_inbox 的 share_at 】",format = "datetime")
        LocalDateTime sharedAt;

        /**
         * 发布者的用户 ID
         */
        @Schema(description = "发布者的用户 ID",format = "uuid")
        UUID actorUserId;

        /**
         * 信箱一条分享记录的类型：【瞬间、时光】
         */
        @Schema(description = "信箱一条分享记录的类型：【瞬间、时光】")
        ObjectType objectType;

        /**
         * 分享对象的 ID：【瞬间 ID、时光 ID】
         */
        @Schema(description = "分享对象的 ID：【瞬间 ID、时光 ID】",format = "uuid")
        UUID objectId;
}
