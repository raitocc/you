package com.you.youbackend.moment.response;

import com.you.youbackend.moment.model.MomentMood;

import java.time.Instant;
import java.util.UUID;

/**
 * 本人瞬间流中的卡片摘要。
 * TODO：补充字段中文注释和 @Schema，说明正文预览长度、封面来源、图片数量、心情及发生时间。
 */
public record MomentFeedItem(
        UUID id,
        String title,
        String bodyPreview,
        String coverUrl,
        int imageCount,
        MomentMood mood,
        Instant memoryTime
) {}
