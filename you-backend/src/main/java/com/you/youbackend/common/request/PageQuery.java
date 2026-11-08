package com.you.youbackend.common.request;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 从 GET 查询参数解析泛型分页请求，同时触发 limit 和内层游标的字段校验。 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PageQuery {
}
