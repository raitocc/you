package com.you.common.exception;

public class BusinessException extends RuntimeException {
  public final int status;
  public final String code;

  public BusinessException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static BusinessException invalid() {
    return new BusinessException(400, "INVALID_PARAMETER", "请求参数无效");
  }

  public static BusinessException unauthorized() {
    return new BusinessException(401, "UNAUTHORIZED", "登录状态无效");
  }

  public static BusinessException missing() {
    return new BusinessException(404, "RESOURCE_NOT_FOUND", "资源不存在或无访问资格");
  }

  public static BusinessException storage() {
    return new BusinessException(503, "STORAGE_UNAVAILABLE", "存储服务暂时不可用");
  }
}
