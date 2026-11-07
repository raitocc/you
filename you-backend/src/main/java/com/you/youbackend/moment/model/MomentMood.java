package com.you.youbackend.moment.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 瞬间的八种心情，接口和数据库统一使用英文枚举名称。
 * Jackson 默认按枚举名称读写 JSON，MyBatis 默认按名称存取，无需自定义转换方法。
 */
@Schema(description = "心情：HAPPY 开心、CALM 平静、RELAXED 松弛、SMITTEN 心动、" +
        "SURPRISED 惊喜、CAREFREE 自在、LOW 低落、ANXIOUS 焦虑")
public enum MomentMood {
    /** 开心：愉悦与满足。 */
    HAPPY,

    /** 平静：内心安宁。 */
    CALM,

    /** 松弛：轻松与放下。 */
    RELAXED,

    /** 心动：喜欢与悸动。 */
    SMITTEN,

    /** 惊喜：意外的美好。 */
    SURPRISED,

    /** 自在：舒适与自由。 */
    CAREFREE,

    /** 低落：失落与难过。 */
    LOW,

    /** 焦虑：担忧与不安。 */
    ANXIOUS
}
