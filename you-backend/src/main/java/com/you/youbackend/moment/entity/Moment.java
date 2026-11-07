package com.you.youbackend.moment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.you.youbackend.moment.model.MomentMood;
import com.you.youbackend.moment.mapper.MomentMoodTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * TODO：补充实体类及字段的中文注释，说明发生时间、发布时间和软删除等业务含义。
 */
@Getter
@Setter
@TableName(value = "moment", autoResultMap = true)
public class Moment {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID authorUserId;
    private String title;
    private String body;
    // TODO：配合心情数据迁移移除指定的兼容处理器，使用默认枚举映射，并清理相关 import。
    @TableField(typeHandler = MomentMoodTypeHandler.class)
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
