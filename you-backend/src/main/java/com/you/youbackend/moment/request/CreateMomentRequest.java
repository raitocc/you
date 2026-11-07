package com.you.youbackend.moment.request;

import com.you.youbackend.common.validation.UUIDv7;
import com.you.youbackend.moment.model.MomentLocation;
import com.you.youbackend.moment.model.MomentMood;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TODO：补充中文类注释和字段 @Schema，说明可空值、图片顺序、标签及时间语义。
 */
public record CreateMomentRequest(
        @Schema(format = "uuid", description = "客户端生成的 UUIDv7，最多允许超前服务端 5 分钟")
        @NotNull(message = "请填写瞬间 ID")
        @UUIDv7
        UUID id,

        @Schema(maxLength = 255)
        @CodePointLength(max = 255, message = "标题不能超过 255 字符") String title,
        String body,

        List<@NotNull(message = "图片 ID 不能为空") UUID> imageFileIds,

        List<@NotNull(message = "标签不能为 null") String> tags,

        MomentMood mood,

        @Schema(format = "date-time", description = "带时区偏移的 ISO 8601 发生时间")
        @NotNull(message = "请填写发生时间") Instant memoryTime,
        @Valid
        MomentLocation location
) {}
