package com.you.youbackend.circle.response;
import com.you.youbackend.circle.model.CircleShareObjectType;
import com.you.youbackend.moment.response.MomentResponse;
import com.you.youbackend.user.response.PublicUserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import java.time.Instant;
import java.util.UUID;
/** 圈子分享列表中的一条记录。分享瞬间时填 moment，分享时光时填 album，另一个字段为 null。 */
@Builder
public record CircleShareFeedResponse(
        @Schema(description = "分享关系 UUIDv7") UUID id,
        @Schema(description = "目标圈子 UUID") UUID circleId,
        @Schema(description = "分享类型，moment 或 album") CircleShareObjectType objectType,
        @Schema(description = "原对象（瞬间/时光）的 UUID") UUID objectId,
        @Schema(description = "分享者公开资料") PublicUserResponse sharer,
        @Schema(description = "本次分享时间，UTC；重复分享不改变") Instant sharedAt,
        @Schema(description = "分享关系最后修改时间，UTC") Instant updatedAt,
        @Schema(description = "当前用户为分享者或圈子创建者时为 true") boolean canWithdraw,
        @Schema(description = "moment 类型的完整瞬间；album 类型为 null") MomentResponse moment,
        @Schema(description = "album 类型的时光卡片；moment 类型为 null") CircleSharedAlbumResponse album
) {}
