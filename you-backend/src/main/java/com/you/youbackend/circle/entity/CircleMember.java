package com.you.youbackend.circle.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;
@Getter
@Setter
@TableName("circle_member")
public class CircleMember {
    /** 成员关系 ID。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 圈子 ID。 */
    private UUID circleId;
    /** 成员用户 ID。 */
    private UUID userId;
    /** 创建时间，UTC 毫秒精度。 */
    private LocalDateTime createdAt;
    /** 修改时间，UTC 毫秒精度。 */
    private LocalDateTime updatedAt;
    /** 是否限制再次加入，新增关系由数据库默认为 false。 */
    private Boolean joinRestricted;
    /** 删除时间，null 表示有效。 */
    private LocalDateTime deletedAt;
}
