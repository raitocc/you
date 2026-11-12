package com.you.youbackend.gathering.validation;

import com.you.youbackend.common.util.InviteCodeGenerator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 长期同行邀请码的共用校验：非空白，最多 16 字符，与面对面短码独立。 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@NotBlank
@Size(max = InviteCodeGenerator.LENGTH)
@ReportAsSingleViolation
public @interface GatheringInviteCode {
    /** 参数校验失败时的提示。 */
    String message() default "请提供非空白且最多 16 字符的同行邀请码";
    /** 校验分组，默认参与通用请求校验。 */
    Class<?>[] groups() default {};
    /** Bean Validation 附加元数据。 */
    Class<? extends Payload>[] payload() default {};
}