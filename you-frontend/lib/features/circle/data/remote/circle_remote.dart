import 'package:dio/dio.dart';
import '../../../../core/network/api_client.dart';

class CircleRemote {
  CircleRemote(this._dio);
  final Dio _dio;

  Future<Object?> get(String path, {Map<String, Object>? query}) async =>
      unwrap(await _dio.get<dynamic>('/circles$path', queryParameters: query));

  Future<Object?> post(String path, [Map<String, Object?>? body]) async =>
      unwrap(await _dio.post<dynamic>('/circles$path', data: body));

  Future<Object?> put(String path, Map<String, Object?> body) async =>
      unwrap(await _dio.put<dynamic>('/circles$path', data: body));

  Future<void> delete(String path) async {
    unwrap(await _dio.delete<dynamic>('/circles$path'));
  }
}
