package com.you.youbackend.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 可预期的业务失败，携带 HTTP 状态、业务错误码和面向调用方的说明。
 * TODO：提供通用参数错误静态工厂方法 invalidArgument(message)，统一 BAD_REQUEST 与 INVALID_ARGUMENT，供各 Service 复用。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public BusinessException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }
}
