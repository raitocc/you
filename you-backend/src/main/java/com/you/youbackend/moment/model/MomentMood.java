package com.you.youbackend.moment.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum MomentMood {
    HAPPY, CALM, RELAXED, SMITTEN, SURPRISED, CAREFREE, LOW, ANXIOUS;

    @JsonCreator
    public static MomentMood fromValue(String value) {
        return MomentMood.valueOf(value);
    }
}
