package com.you.youbackend.gathering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.you.youbackend.gathering.model.GatheringStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** 同行主记录，由创建者管理，软删除沿用全局配置。 */
@Getter
@Setter
@TableName("gathering")
public class Gathering {
    /** 同行 ID，服务端生成的 UUIDv7。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 创建时间，UTC 毫秒精度。 */
    private LocalDateTime createdAt;
    /** 最后修改时间，UTC 毫秒精度。 */
    private LocalDateTime updatedAt;
    /** 创建者 ID，关联 app_user.id，同时具有有效成员关系。 */
    private UUID creatorUserId;
    /** 名称，去首尾空白后 1～128 个 Unicode 码点，允许重名。 */
    private String name;
    /** 介绍，最多 5000 个 Unicode 码点；null 表示未设置。 */
    private String description;
    /** 同行状态：1 进行中，2 已结束。 */
    private GatheringStatus status;
    /** 是否允许通过邀请码加入，创建时开启。 */
    private Boolean inviteEnabled;
    /** 系统生成的唯一长期邀请码，与面对面临时短码独立。 */
    private String inviteCode;
    /** 解散时间，UTC；null 表示有效。 */
    private LocalDateTime deletedAt;
}