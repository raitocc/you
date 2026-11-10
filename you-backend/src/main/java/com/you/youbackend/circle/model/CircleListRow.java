package com.you.youbackend.circle.model;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;
/** 列表查询投影，仅包含摘要字段、头像引用及当前用户成员关系的分页位置。 */
@Getter
@Setter
public class CircleListRow {
    /** 圈子 ID，用于进入详情。 */
    private UUID id;
    /** 圈子名称。 */
    private String name;
    /** 自定义头像引用，null 使用默认头像。 */
    private UUID avatarFileId;
    /** 成员关系创建时间，UTC。 */
    private LocalDateTime joinedAt;
    /** 成员关系 ID，不是圈子 ID。 */
    private UUID membershipId;
}
