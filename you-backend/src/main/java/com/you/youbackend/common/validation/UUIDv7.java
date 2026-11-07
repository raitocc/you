package com.you.youbackend.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 校验 Java UUID 的版本、标准变体及生成时间，供客户端生成的瞬间 ID 使用。
 *
 * <p>请求 JSON 先由 Jackson 转为 UUID，再由 Controller 的 @Valid 触发字段校验；
 * 直接标注在路径参数上时，由 Spring MVC 方法参数校验触发。
 * Constraint 将此注解关联到 UUIDv7Validator，具体规则由校验器执行。
 * Retention(RUNTIME) 使框架能在运行时读取注解；Target 声明允许标注的位置，
 * 包括字段、方法参数、record 组件及集合元素；Documented 使其出现在生成的 API 文档中。</p>
 *
 * <p>发布 ID 默认检查时间戳，允许旧草稿 ID；已有记录的路径 ID 使用
 * {@code @UUIDv7(checkTimestamp = false)}，只检查版本和变体。
 * 本约束允许 null，必填规则由 @NotNull 单独表达。</p>
 */
@Documented
@Constraint(validatedBy = UUIDv7Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
        ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface UUIDv7 {

    /** 版本或变体校验失败时的默认提示，可在使用注解时覆盖。 */
    String message() default "必须是 UUIDv7";

    /** 是否检查生成时间；发布时开启，查询已有记录时关闭。 */
    boolean checkTimestamp() default true;

    /** 允许客户端时钟超前的分钟数，校验器读取此值，时间错误提示也使用同一参数。 */
    long maxFutureMinutes() default 5;

    /** Jakarta Validation 标准属性，用于按校验组选择约束；当前使用默认组。 */
    Class<?>[] groups() default {};

    /** Jakarta Validation 标准属性，供调用方附加约束元信息；当前保持为空。 */
    Class<? extends Payload>[] payload() default {};
}
