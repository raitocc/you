import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/network/api_client.dart';
import '../../models/moment.dart';

final momentRemoteDataSourceProvider = Provider(
  (ref) => MomentRemoteDataSource(ref.watch(dioProvider)),
);

class MomentRemoteDataSource {
  MomentRemoteDataSource(this._dio);

  final Dio _dio;

  /// 按游标查询本人发布的瞬间流。
  Future<Map<String, dynamic>> browseYours({
    MomentFeedCursor? cursor,
    int? limit,
  }) async => _data(
    await _dio.get<dynamic>(
      '/moments/yours',
      queryParameters: <String, dynamic>{
        if (cursor != null) ...{
          'cursor.memoryTime': cursor.memoryTime,
          'cursor.id': cursor.id,
        },
        'limit': ?limit,
      },
    ),
  );

  /// 按 ID 请求瞬间详情。
  Future<Map<String, dynamic>> detail(String id) async =>
      _data(await _dio.get<dynamic>(_path(id)));

  /// 提交新瞬间并读取发布结果。
  Future<Map<String, dynamic>> publish(Map<String, dynamic> payload) async =>
      _data(await _dio.post<dynamic>('/moments', data: payload));

  /// 全量替换本人瞬间的可编辑内容。
  Future<Map<String, dynamic>> edit(
    String id,
    Map<String, dynamic> payload,
  ) async => _data(await _dio.put<dynamic>(_path(id), data: payload));

  /// 删除指定瞬间，成功响应的数据为空。
  Future<void> delete(String id) async =>
      unwrap(await _dio.delete<dynamic>(_path(id)));

  String _path(String id) => '/moments/${Uri.encodeComponent(id)}';

  Map<String, dynamic> _data(Response<dynamic> response) =>
      unwrap(response) as Map<String, dynamic>;
}
