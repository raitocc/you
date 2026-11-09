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

/**
 * 瞬间主记录，保存作者、图文内容、心情、发生时间和地点。
 * 图片及标签通过关联表维护；时间字段均使用 UTC，数据库保存至毫秒。
 */
@Getter
@Setter
@TableName("moment")
public class Moment {

    /** 瞬间 ID，客户端生成的 UUIDv7，使用全局处理器映射为 BINARY(16)。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 作者用户 ID，关联 app_user.id，发布后保持不变。 */
    private UUID authorUserId;

    /** 当前关联的同行 ID，可空；一条瞬间最多属于一个同行。 */
    private UUID gatheringId;

    /** 标题，可空。 */
    private String title;

    /** 普通文本正文，可空；发布、修改时正文和图片至少提供一种。 */
    private String body;

    /** 心情，可空；按枚举名称存储，例如 HAPPY，使用框架默认枚举映射。 */
    private MomentMood mood;

    /** 经历实际发生时间，UTC；可由用户修改，用于瞬间流排序。 */
    private LocalDateTime memoryTime;

    /** 纬度，可空，范围 -90～90；与经度成对填写，保存七位小数。 */
    private BigDecimal latitude;

    /** 经度，可空，范围 -180～180；与纬度成对填写，保存七位小数。 */
    private BigDecimal longitude;

    /** 地点名称，可空，允许只记录地名和地址。 */
    private String locationName;

    /** 详细地址，可空。 */
    private String locationAddress;

    /** 创建时间，即瞬间发布时间，UTC，与经历实际发生时间区分。 */
    private LocalDateTime createdAt;

    /** 最后修改时间，UTC。 */
    private LocalDateTime updatedAt;

    /** 软删除时间，UTC；null 表示有效，由 MyBatis-Plus 全局逻辑删除配置维护。 */
    private LocalDateTime deletedAt;
}
