package com.you.youbackend.common.exception;

import com.you.youbackend.common.response.Result;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局接口异常处理，将请求错误转换为统一响应。
 */
@Log4j2
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理请求对象的字段校验失败，返回其中一条校验提示。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(
            MethodArgumentNotValidException exception) {

        FieldError error = exception.getBindingResult().getFieldError();
        String message = error == null
                ? "请求参数不符合要求"
                : error.getDefaultMessage();

        return Result.fail("INVALID_ARGUMENT", message);
    }

    /**
     * 将路径或查询参数的校验错误转换为统一响应。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HandlerMethodValidationException.class)
    public Result<Void> handleMethodValidation(HandlerMethodValidationException exception) {
        String message = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(error -> error.getDefaultMessage())
                .findFirst().orElse("请求参数不符合要求");
        return Result.fail("INVALID_ARGUMENT", message);
    }

    /**
     * 将无法转换类型的请求参数统一返回为参数错误。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return Result.fail("INVALID_ARGUMENT", "请求参数格式无效");
    }

    /**
     * 处理 JSON 格式错误、字段类型错误或无法识别的枚举值。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadableBody(
            HttpMessageNotReadableException exception) {

        return Result.fail(
                "INVALID_REQUEST_BODY",
                "请求体格式、字段类型或枚举值不正确");
    }

    /**
     * 将服务端代码缺陷（如内部调用违反前置约定）转换为 500，详细原因只进日志不外抛。
     */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException exception) {
        log.error("服务端参数前置约定被违反", exception);
        return Result.fail("INTERNAL_ERROR", "服务器内部错误");
    }

    /**
     * 将业务异常携带的状态、错误码和说明转换为统一响应。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(Result.fail(exception.getCode(), exception.getMessage()));
    }
}
