package com.you.youbackend.album.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.you.youbackend.album.model.AlbumOrganizeMode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** 时光主记录，软删除沿用 MyBatis-Plus 全局配置。 */
@Getter
@Setter
@TableName("album")
public class Album {
    /** 时光 ID，服务端生成的 UUIDv7。 */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所有者 ID，关联 app_user.id。 */
    private UUID ownerUserId;
    /** 名称，去首尾空白后 1～128 字符，允许重名。 */
    private String name;
    /** 介绍，最多 5000 字符；null 表示未设置。 */
    private String description;
    /** 整理方式，手动创建固定为 manual。 */
    private AlbumOrganizeMode organizeMode;
    /** AI 整理要求，手动创建为空。 */
    private String organizePrompt;
    /** 封面文件 ID；null 表示无封面。 */
    private UUID coverFileId;
    /** 创建时间，UTC 毫秒精度。 */
    private LocalDateTime createdAt;
    /** 最后修改时间，UTC 毫秒精度。 */
    private LocalDateTime updatedAt;
    /** 删除时间，UTC；null 表示有效。 */
    private LocalDateTime deletedAt;
}
