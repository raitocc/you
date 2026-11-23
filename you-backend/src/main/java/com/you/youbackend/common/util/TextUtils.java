package com.you.youbackend.common.util;

public final class TextUtils {

    public static int countCodePoints(String s) {
        return s.codePointCount(0, s.length());
    }

    public static String truncateByCodePoints(String str, int limit) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        int codePointCount = countCodePoints(str);
        limit = Math.min(limit, codePointCount);
        int end = str.offsetByCodePoints(0, limit);
        return str.substring(0, end);
    }
}
