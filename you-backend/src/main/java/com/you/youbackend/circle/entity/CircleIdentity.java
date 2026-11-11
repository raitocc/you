package com.you.youbackend.circle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;

import java.util.UUID;

/** 圈子主键投影，查询范围包含已解散的圈子。 */
@Getter
@TableName("circle")
public class CircleIdentity {
    /** 圈子主键。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
}
