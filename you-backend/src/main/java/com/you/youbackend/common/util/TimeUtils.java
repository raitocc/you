package com.you.youbackend.common.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class TimeUtils {

    private TimeUtils() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}