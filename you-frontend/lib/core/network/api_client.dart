import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/config/app_config.dart';
import '../auth/session.dart';
import '../errors/app_exception.dart';

// 封装 Dio 客户端，提供统一的 API 请求和响应处理逻辑。
// 后端 API 客户端
final dioProvider = Provider<Dio>((ref) {
  final dio = Dio(
    BaseOptions(
      baseUrl: AppConfig.apiBaseUrl,
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 30),
    ),
  );

  // dio 拦截器：可以在 HTTP 请求发送前、收到响应后、请求发生错误时执行额外逻辑。
  // dio 提供了三种拦截器：onRequest、onResponse、onError。
  // onRequest：在请求发送前执行，可以修改请求参数、添加请求头等。
  // onResponse：在收到响应后执行，可以对响应数据进行处理、解析等
  // onError：在请求发生错误时执行，可以处理错误、重试请求等。
  dio.interceptors.add(
    InterceptorsWrapper(
      // 下面的业务逻辑是在请求发送之前自动携带 Token，避免每次请求都手动添加 Authorization 请求头。
      onRequest: (options, handler) {
        // 从 Riverpod 的 sessionProvider 中读取当前会话的 Token，如果存在，则将其添加到请求头中；如果不存在，则移除 Authorization 请求头。
        final token = ref.read(sessionProvider).asData?.value;

        if (token != null) {
          options.headers['Authorization'] = 'Bearer $token';
        } else {
          options.headers.remove('Authorization');
        }

        handler.next(options);
      },
      // 在请求发生错误时执行的逻辑。主要用于处理 401 未授权错误，清除会话，并将后端返回的错误信息封装为 AppException。
      onError: (error, handler) {
        final token = ref.read(sessionProvider).asData?.value;

        // 仅当失败请求携带的 Token
        // 仍是当前 Token 时清除会话
        final requestToken = error.requestOptions.headers['Authorization'];

        if (error.response?.statusCode == 401 &&
            token != null &&
            requestToken == 'Bearer $token') {
          ref.read(sessionProvider.notifier).clear();
        }

        final body = error.response?.data;

        if (body is Map &&
            body['code'] is String &&
            body['message'] is String) {
          handler.next(
            error.copyWith(
              error: AppException(
                body['code'] as String,
                body['message'] as String,
              ),
            ),
          );
        } else {
          handler.next(error);
        }
      },
    ),
  );

  ref.onDispose(() => dio.close(force: true));

  return dio;
});

// OSS 独立客户端
final storageDioProvider = Provider<Dio>((ref) {
  final dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 30),
      sendTimeout: const Duration(seconds: 60),
    ),
  );

  ref.onDispose(() => dio.close(force: true));

  return dio;
});

// 统一处理后端响应数据
Object? unwrap(Response<dynamic> response) {
  final body = response.data as Map<String, dynamic>;
  if (body['code'] != 'SUCCESS') {
    throw AppException(body['code'] as String, body['message'] as String);
  }
  return body['data'];
}

// 统一处理错误信息
String errorMessage(Object? error) {
  if (error is AppException) return error.message;
  if (error is DioException) {
    if (error.error is AppException) {
      return (error.error as AppException).message;
    }
    if (error.type == DioExceptionType.cancel) return '请求已取消，请重试';
    return '请求失败，请检查网络后重试';
  }
  return '操作失败，请重试';
}
