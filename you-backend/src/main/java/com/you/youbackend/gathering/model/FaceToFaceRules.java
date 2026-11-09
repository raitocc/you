package com.you.youbackend.gathering.model;

import java.time.Duration;

/** 面对面邀请的固定窗口、附近查询范围和 GEO Key 前缀。 */
public final class FaceToFaceRules {
    /** 邀请在同行创建后 10 分钟截止，同时按此长度划分 GEO 时间段。 */
    public static final Duration WINDOW = Duration.ofMinutes(10);
    /** 预览候选的最大距离，单位米；确认加入通过数据库校验短码。 */
    public static final double RADIUS_METERS = 100;
    /** 后接短码与时间段起点的 Unix 秒数，每段独立到期。 */
    public static final String GEO_KEY_PREFIX = "you:gathering:face:geo:";

    private FaceToFaceRules() {
    }
}