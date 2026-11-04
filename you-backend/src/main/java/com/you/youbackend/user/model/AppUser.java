package com.you.youbackend.user.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户账号及个人资料。
 */
@Getter
@Setter
@TableName("app_user")
public class AppUser {

    @TableId(type = IdType.INPUT)
    private UUID id;

    private String username;

    private String passwordHash;

    private String nickname;

    private UUID avatarFileId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}