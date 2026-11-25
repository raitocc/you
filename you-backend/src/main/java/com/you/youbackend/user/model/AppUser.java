package com.you.youbackend.user.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户账号及个人资料，软删除沿用 MyBatis-Plus 全局配置。
 */
@Getter
@Setter
@TableName("app_user")
public class AppUser {

    /** 用户 ID，服务端生成的 UUIDv7。 */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /** 登录账号，注册时统一转小写，唯一。 */
    private String username;

    /** 密码散列，只进不出，禁止出现在任何响应里。 */
    private String passwordHash;

    /** 昵称，去除首尾空白后 1～64 字符。 */
    private String nickname;

    /** 头像文件 ID，关联 media_file.id；null 表示未设置。 */
    private UUID avatarFileId;

    /** 创建时间，UTC 毫秒精度。 */
    private Instant createdAt;

    /** 最后修改时间，UTC 毫秒精度。 */
    private Instant updatedAt;

    /** 删除时间，UTC；null 表示有效。 */
    private Instant deletedAt;
}
