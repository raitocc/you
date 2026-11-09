package com.you.youbackend.gathering.model;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 同行状态，通过 code 与数据库 TINYINT 字段双向映射。 */
@Getter
@RequiredArgsConstructor
public enum GatheringStatus {
    /** 进行中，数据库保存为 1。 */
    ONGOING(1),
    /** 已结束，数据库保存为 2。 */
    ENDED(2);

    /** 持久化编码，MyBatis-Plus 使用该值写入和还原枚举。 */
    @EnumValue
    private final int code;
}