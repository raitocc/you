package com.you.youbackend.moment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.you.youbackend.moment.model.MomentMood;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@TableName("moment")
public class Moment {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID authorUserId;
    private String title;
    private String body;
    private MomentMood mood;
    private LocalDateTime memoryTime;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String locationName;
    private String locationAddress;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
