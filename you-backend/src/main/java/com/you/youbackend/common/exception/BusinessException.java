package com.you.youbackend.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 可预期的业务失败，由工厂方法统一 HTTP 状态，业务代码提供错误码和提示。
 * 全局异常处理器将异常转换为 Result 响应；带 cause 的重载保留底层异常链。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private BusinessException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /**
     * 构造 HTTP 400、INVALID_ARGUMENT 的通用参数错误。
     * @param message 面向调用方的具体说明
     * @return 异常对象，由调用方使用 throw 抛出
     */
    public static BusinessException invalidArgument(String message) {
        return badRequest("INVALID_ARGUMENT", message);
    }

    /** 构造 HTTP 401、UNAUTHORIZED 的认证失败。 */
    public static BusinessException unauthorized(String message) {
        return unauthorized("UNAUTHORIZED", message);
    }

    /** 构造 HTTP 401、UNAUTHORIZED 的认证失败，并保留底层异常。 */
    public static BusinessException unauthorized(String message, Throwable cause) {
        return unauthorized("UNAUTHORIZED", message, cause);
    }

    /** 构造 HTTP 403、FORBIDDEN 的权限不足错误。 */
    public static BusinessException forbidden(String message) {
        return forbidden("FORBIDDEN", message);
    }

    /**
     * 构造 HTTP 400，请求不符合业务要求的异常。
     * @param code 具体业务错误码
     * @param message 面向调用方的具体说明
     * @return 异常对象，由调用方使用 throw 抛出
     */
    public static BusinessException badRequest(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message, null);
    }

    /**
     * 构造 HTTP 401，认证失败的异常。
     * @param code 具体业务错误码
     * @param message 面向调用方的具体说明
     * @return 异常对象，由调用方使用 throw 抛出
     */
    public static BusinessException unauthorized(String code, String message) {
        return unauthorized(code, message, null);
    }

    /**
     * 构造 HTTP 401，认证失败的异常，并保留异常链。
     * @param code 具体业务错误码
     * @param message 面向调用方的具体说明
     * @param cause 原始异常
     * @return 异常对象，由调用方使用 throw 抛出
     */
    public static BusinessException unauthorized(String code, String message, Throwable cause) {
        return new BusinessException(HttpStatus.UNAUTHORIZED, code, message, cause);
    }

    /**
     * 构造 HTTP 403，权限不足的异常。
     * @param code 具体业务错误码
     * @param message 面向调用方的具体说明
     * @return 异常对象，由调用方使用 throw 抛出
     */
    public static BusinessException forbidden(String code, String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, code, message, null);
    }

    /**
     * 构造 HTTP 404，资源不存在的异常。
     * @param code 具体业务错误码
     * @param message 面向调用方的具体说明
     * @return 异常对象，由调用方使用 throw 抛出
     */
    public static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message, null);
    }

    /**
     * 构造 HTTP 409，资源冲突的异常。
     * @param code 具体业务错误码
     * @param message 面向调用方的具体说明
     * @return 异常对象，由调用方使用 throw 抛出
     */
    public static BusinessException conflict(String code, String message) {
        return conflict(code, message, null);
    }

    /**
     * 构造 HTTP 409，资源冲突的异常，并保留异常链。
     * @param code 具体业务错误码
     * @param message 面向调用方的具体说明
     * @param cause 原始异常
     * @return 异常对象，由调用方使用 throw 抛出
     */
    public static BusinessException conflict(String code, String message, Throwable cause) {
        return new BusinessException(HttpStatus.CONFLICT, code, message, cause);
    }

    /**
     * 构造 HTTP 502，上游服务调用失败的异常。
     * @param code 具体业务错误码
     * @param message 面向调用方的具体说明
     * @return 异常对象，由调用方使用 throw 抛出
     */
    public static BusinessException badGateway(String code, String message) {
        return badGateway(code, message, null);
    }

    /**
     * 构造 HTTP 502，上游服务调用失败的异常，并保留异常链。
     * @param code 具体业务错误码
     * @param message 面向调用方的具体说明
     * @param cause 原始异常
     * @return 异常对象，由调用方使用 throw 抛出
     */
    public static BusinessException badGateway(String code, String message, Throwable cause) {
        return new BusinessException(HttpStatus.BAD_GATEWAY, code, message, cause);
    }
}
