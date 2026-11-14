package com.you.youbackend.inbox.response;

import com.you.youbackend.inbox.model.InboxSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** 动态来源空间，点击后通过类型与 ID 打开对应同行或圈子。 */
@Schema(description = "动态来源")
public record RelatedSourceResponse(
        @Schema(description = "来源类型，gathering 或 circle") InboxSourceType type,
        @Schema(description = "来源空间 ID", format = "uuid") UUID id,
        @Schema(description = "当前空间名称") String name
) {}
