package com.you.youbackend.inbox.model;

import com.you.youbackend.common.validation.UUIDv7;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/** 上一页最后一条动态的分享时间与信箱 ID，按两者倒序继续查询。 */
@Schema(description = "Related 分页游标")
public record RelatedCursor(
        @NotNull(message = "请提供游标分享时间")
        @Schema(description = "上一页最后一条动态的分享时间，UTC") Instant sharedAt,
        @NotNull(message = "请提供游标动态 ID") @UUIDv7(checkTimestamp = false)
        @Schema(description = "同一条信箱动态的 UUIDv7", format = "uuid") UUID id
) {}
