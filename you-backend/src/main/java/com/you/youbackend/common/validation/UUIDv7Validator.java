package com.you.youbackend.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 执行 UUIDv7 约束：泛型第一个参数是约束注解，第二个参数是支持校验的 Java 类型。
 *
 * <p>无需手动调用或注册为 Service，校验框架根据 @Constraint 创建校验器，先调用
 * initialize 读取注解配置，再调用 isValid 检查字段值。返回 false 会形成约束错误，
 * 交给已有全局异常处理器转换为统一响应，校验器本身不抛业务异常。</p>
 */
public class UUIDv7Validator implements ConstraintValidator<UUIDv7, UUID> {

    private boolean checkTimestamp;
    private Duration maxFutureSkew;

    /** 读取本次约束的固定配置；校验器可能被复用，每次请求的数据只使用局部变量。 */
    @Override
    public void initialize(UUIDv7 annotation) {
        checkTimestamp = annotation.checkTimestamp();
        maxFutureSkew = Duration.ofMinutes(annotation.maxFutureMinutes());
    }

    /**
     * 校验版本、变体，按配置检查时间上限；允许历史时间以支持旧草稿发布。
     *
     * @param value Jackson 或 MVC 已转换好的 UUID；null 留给 @NotNull 检查
     * @param context 用于构造校验失败提示，由框架提供
     * @return 满足约束或值为空时返回 true，违反任一规则时返回 false
     */
    @Override
    public boolean isValid(UUID value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        // version=7 表示 UUIDv7；variant=2 表示 RFC 标准变体，其最高位模式为二进制 10。
        if (value.version() != 7 || value.variant() != 2) {
            return false;
        }
        if (!checkTimestamp) {
            return true;
        }

        // UUIDv7 高 64 位的前 48 位存储 Unix 毫秒时间戳，逻辑右移 16 位即可提取。
        long timestamp = value.getMostSignificantBits() >>> 16;
        long latestAllowed = Instant.now().plus(maxFutureSkew).toEpochMilli();
        if (timestamp > latestAllowed) {
            // 用具体时间提示替代默认提示；占位符由校验框架读取注解参数后插值。
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "UUIDv7 生成时间最多允许超前服务端 {maxFutureMinutes} 分钟")
                    .addConstraintViolation();
            return false;
        }
        return true;
    }
}
