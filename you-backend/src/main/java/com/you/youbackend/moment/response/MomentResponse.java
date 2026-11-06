package com.you.youbackend.moment.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.you.youbackend.moment.model.MomentLocation;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** 当前访问者可见的瞬间数据。 */
@Schema(description = "瞬间数据")
public record MomentResponse(
        @Schema(description = "瞬间 ID") UUID id,
        @Schema(description = "公开作者资料") Author author,
        @Schema(description = "标题") String title,
        @Schema(description = "完整普通文本正文") String body,
        @Schema(description = "心情") String mood,
        @Schema(description = "按展示顺序排列的图片") List<Image> images,
        @Schema(description = "标签名称") List<String> tags,
        @Schema(description = "发生时间，UTC") String memoryTime,
        @Schema(description = "发生地点") MomentLocation location,
        @Schema(description = "当前可见来源") List<Source> sources,
        @Schema(description = "当前可见时光，仅详情返回")
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Collection> collections,
        @Schema(description = "是否本人发布") @JsonProperty("isOwn") boolean isOwn,
        @Schema(description = "是否可编辑") boolean canEdit,
        @Schema(description = "是否可删除") boolean canDelete,
        @Schema(description = "发布时间，UTC") String createdAt,
        @Schema(description = "最后修改时间，UTC，用作版本凭证") String updatedAt
) {
    public record Author(UUID id, String nickname, UUID avatarFileId, String avatarUrl) {}
    public record Image(UUID fileId, int sortOrder, String url, String mimeType) {}
    public record Source(String type, UUID id, String name, String entryPath) {}
    public record Collection(UUID id, String name, String entryPath) {}
}
