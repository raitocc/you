package com.you.youbackend.moment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 瞬间的标签记录，标签名称直接归属于当前瞬间。
 * 同一瞬间内标签名称按数据库唯一约束去重，移除时软删除对应记录。
 */
@Getter
@Setter
@TableName("moment_tag")
public class MomentTag {

    /** 标签记录 ID，服务端生成的 UUIDv7。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 所属瞬间 ID，关联 moment.id。 */
    private UUID momentId;

    /** 标签名称，去首尾空白后非空，最多 64 个 Unicode 码点。 */
    private String tagName;

    /** 标签记录首次创建时间，UTC。 */
    private LocalDateTime createdAt;

    /** 标签记录最后修改时间，UTC。 */
    private LocalDateTime updatedAt;

    /** 标签移除时间，UTC；null 表示有效，重新添加时恢复原记录。 */
    private LocalDateTime deletedAt;
}
