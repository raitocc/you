import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/network/api_client.dart';

final momentRemoteDataSourceProvider = Provider(
  (ref) => MomentRemoteDataSource(ref.watch(dioProvider)),
);

class MomentRemoteDataSource {
  MomentRemoteDataSource(this._dio);

  final Dio _dio;

  /// 按游标查询本人发布的瞬间流。
  Future<Map<String, dynamic>> browseYours(String? cursor) async => _data(
    await _dio.get<dynamic>(
      '/moments/yours',
      queryParameters: {'cursor': ?cursor},
    ),
  );

  /// 按 ID 请求瞬间详情。
  Future<Map<String, dynamic>> detail(String id) async =>
      _data(await _dio.get<dynamic>(_path(id)));

  /// 提交新瞬间并读取发布结果。
  Future<Map<String, dynamic>> publish(Map<String, dynamic> payload) async =>
      _data(await _dio.post<dynamic>('/moments', data: payload));

  /// 通过 If-Match 提交当前版本的编辑。
  Future<Map<String, dynamic>> edit(
    String id,
    String version,
    Map<String, dynamic> payload,
  ) async => _data(
    await _dio.patch<dynamic>(
      _path(id),
      data: payload,
      options: _version(version),
    ),
  );

  /// 通过 If-Match 删除指定版本的瞬间。
  Future<void> delete(String id, String version) async =>
      unwrap(await _dio.delete<dynamic>(_path(id), options: _version(version)));

  String _path(String id) => '/moments/${Uri.encodeComponent(id)}';

  Options _version(String version) =>
      Options(headers: {'If-Match': '"$version"'});

  Map<String, dynamic> _data(Response<dynamic> response) =>
      unwrap(response) as Map<String, dynamic>;
}
