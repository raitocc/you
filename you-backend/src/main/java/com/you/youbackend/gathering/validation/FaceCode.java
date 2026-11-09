package com.you.youbackend.gathering.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 面对面短码共用约束：必填，四位 ASCII 数字，保留前导零。 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@NotNull
@Pattern(regexp = "[0-9]{4}")
@ReportAsSingleViolation
public @interface FaceCode {
    /** 校验失败时的提示。 */
    String message() default "面对面短码须为四位数字";
    /** Jakarta Validation 校验分组。 */
    Class<?>[] groups() default {};
    /** Jakarta Validation 附加元数据。 */
    Class<? extends Payload>[] payload() default {};
}