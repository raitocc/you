package com.you.youbackend.circle.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;
/**
 * 圈子成员关系。退出或被移除只软删不清行，未被限制加入的可恢复同一条关系（保留原 ID 与创建时间）；
 * 被移除的成员带 joinRestricted 标记，限制再次加入。
 */
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
    private Instant createdAt;
    /** 修改时间，UTC 毫秒精度。 */
    private Instant updatedAt;
    /** 是否限制再次加入，新增关系由数据库默认为 false。 */
    private Boolean joinRestricted;
    /** 离开或移除时间，UTC；null 表示有效成员。 */
    private Instant deletedAt;
}
