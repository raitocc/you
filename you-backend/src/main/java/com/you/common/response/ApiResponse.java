package com.you.common.response;

public record ApiResponse<T>(String code, String message, T data) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>("SUCCESS", "操作成功", data);
  }
}
