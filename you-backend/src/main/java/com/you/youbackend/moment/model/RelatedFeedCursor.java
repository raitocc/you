package com.you.youbackend.moment.model;

import com.you.youbackend.common.validation.UUIDv7;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "\"与你有关\" Tab 搜索游标")
public record RelatedFeedCursor(
        /**
         * 信箱分享时间：【信箱 user_inbox 的 share_at】
         */
        @NotNull(message = "请提供游标分享时间")
        @Schema(description = "信箱分享时间：【信箱 user_inbox 的 share_at】")
        LocalDateTime sharedAt,

        /**
         * 信箱 ID：【信箱 user_inbox 的 id】
         */
        @NotNull(message = "请提供游标动态 ID")
        @UUIDv7(checkTimestamp = false)
        @Schema(description = "信箱 ID：【信箱 user_inbox 的 id】")
        UUID id
) {
}
