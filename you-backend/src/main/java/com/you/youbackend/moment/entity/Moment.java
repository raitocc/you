package com.you.youbackend.moment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** 瞬间主记录，时间按 UTC 保存。 */
@Getter
@Setter
@TableName("moment")
public class Moment {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID authorUserId;
    private UUID gatheringId;
    private String title;
    private String body;
    private String mood;
    private LocalDateTime memoryTime;
    private String locationName;
    private String locationAddress;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
