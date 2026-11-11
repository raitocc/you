package com.you.youbackend.circle.response;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
/** 圈子分享列表里的时光卡片。返回名称、简介、封面和当前瞬间数，简介和封面可以为空。 */
public record CircleSharedAlbumResponse(
        @Schema(description = "时光 UUIDv7") UUID id,
        @Schema(description = "时光名称") String name,
        @Schema(description = "完整时光简介；未设置为 null") String description,
        @Schema(description = "有效封面签名地址；无封面或文件失效为 null") String coverUrl,
        @Schema(description = "时光中当前有效的本人瞬间数量，空时光为 0") long momentCount) {}
