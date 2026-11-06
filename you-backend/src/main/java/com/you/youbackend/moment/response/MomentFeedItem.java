package com.you.youbackend.moment.response;

import com.you.youbackend.moment.model.MomentMood;

import java.time.Instant;
import java.util.UUID;

/**
 * 本人瞬间流中的卡片摘要。
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
