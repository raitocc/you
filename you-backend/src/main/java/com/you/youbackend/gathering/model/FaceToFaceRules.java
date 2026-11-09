package com.you.youbackend.gathering.model;

import java.time.Duration;

/** 面对面加入的固定业务规则与 Redis Key 前缀。 */
public final class FaceToFaceRules {
    /** 从同行创建时间起计算的面对面窗口，重复开启保持原截止时间。 */
    public static final Duration WINDOW = Duration.ofMinutes(10);
    /** 加入者距离入口发起位置的最大距离，单位米。 */
    public static final double RADIUS_METERS = 100;
    /** 每个同行单独保存的临时入口，设置独立过期时间。 */
    public static final String ENTRY_KEY_PREFIX = "you:gathering:face:entry:";
    /** 同一短码的位置索引，允许包含不同地点或同一地点的多个同行。 */
    public static final String GEO_KEY_PREFIX = "you:gathering:face:geo:";

    private FaceToFaceRules() {
    }
}