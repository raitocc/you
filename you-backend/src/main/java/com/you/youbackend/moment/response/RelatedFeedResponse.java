package com.you.youbackend.moment.response;

import com.you.youbackend.album.response.AlbumSummaryResponse;
import com.you.youbackend.common.enumeration.ObjectType;
import com.you.youbackend.user.response.PublicUserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "信箱的一条分享记录")
public record RelatedFeedResponse(
        /**
         * 信箱 ID：【user_inbox 的 id】
         */
        @Schema(description = "信箱 ID：【user_inbox 的 id】",format = "uuid")
        UUID id,

        /**
         * 信箱分享时间：【user_inbox 的 share_at 】
         */
        @Schema(description = "信箱分享时间：【user_inbox 的 share_at 】",format = "datetime")
        LocalDateTime sharedAt,

        /**
         * 信箱一条分享记录的类型：【瞬间、时光】
         */
        @Schema(description = "信箱一条分享记录的类型：【瞬间、时光】")
        ObjectType objectType,

        /**
         * 信箱一条分享记录的发布者
         */
        @Schema(description = "信箱一条分享记录的发布者")
        PublicUserResponse actor,

        /**
         * 信箱一条分享记录对应的瞬间概要信息，MomentSummaryResponse
         */
        @Schema(description = "信箱一条分享记录对应的瞬间概要信息，MomentSummaryResponse")
        MomentSummaryResponse momentSummary,

        /**
         * 信箱一条分享记录对应的时光概要信息，AlbumSummaryResponse
         */
        @Schema(description = "信箱一条分享记录对应的时光概要信息，AlbumSummaryResponse")
        AlbumSummaryResponse albumSummary
) {
}
