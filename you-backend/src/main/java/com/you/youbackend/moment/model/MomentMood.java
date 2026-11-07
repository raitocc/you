package com.you.youbackend.moment.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * TODO：补充八种心情的中文名称和含义注释。
 * TODO：移除仅重复默认枚举解析行为的 @JsonCreator 和 fromValue 方法。
 */
public enum MomentMood {
    HAPPY, CALM, RELAXED, SMITTEN, SURPRISED, CAREFREE, LOW, ANXIOUS;

    @JsonCreator
    public static MomentMood fromValue(String value) {
        return MomentMood.valueOf(value);
    }
}
