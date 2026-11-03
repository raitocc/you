package com.you.common.exception;

import com.you.common.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<?> business(BusinessException e) {
    return ResponseEntity.status(e.status).body(new ApiResponse<>(e.code, e.getMessage(), null));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  public ResponseEntity<?> invalid(Exception e) {
    return business(BusinessException.invalid());
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<?> method(Exception e) {
    return ResponseEntity.status(405)
        .body(new ApiResponse<>("METHOD_NOT_ALLOWED", "请求方法不支持", null));
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<?> unsupported(Exception e) {
    return ResponseEntity.status(415)
        .body(new ApiResponse<>("UNSUPPORTED_MEDIA_TYPE", "请求类型不支持", null));
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<?> missing(Exception e) {
    return business(BusinessException.missing());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    return ResponseEntity.internalServerError()
        .body(new ApiResponse<>("INTERNAL_ERROR", "服务暂时不可用", null));
  }
}
