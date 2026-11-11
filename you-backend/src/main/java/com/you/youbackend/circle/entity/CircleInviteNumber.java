package com.you.youbackend.circle.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

/** 圈子邀请码分配记录，主键由数据库自增生成。 */
@Getter
@Setter
@TableName("circle_invite_number")
public class CircleInviteNumber {
    /** 从 10000 开始分配的邀请码。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 领取该号码的圈子 ID。 */
    private UUID circleId;
}
