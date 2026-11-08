import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/network/api_client.dart';
import '../../models/album.dart';

final albumRemoteProvider = Provider(
  (ref) => AlbumRemoteDataSource(ref.watch(dioProvider)),
);

class AlbumRemoteDataSource {
  AlbumRemoteDataSource(this._dio);
  final Dio _dio;
  Future<Map<String, dynamic>> browse({
    AlbumListCursor? cursor,
    int? limit,
  }) async =>
      unwrap(
            await _dio.get<dynamic>(
              '/albums',
              queryParameters: {
                'limit': ?limit,
                if (cursor != null) ...{
                  'cursor.createdAt': cursor.createdAt,
                  'cursor.id': cursor.id,
                },
              },
            ),
          )
          as Map<String, dynamic>;

  Future<Map<String, dynamic>> browseMoments(
    String id, {
    AlbumTimelineCursor? cursor,
    int? limit,
  }) async =>
      unwrap(
            await _dio.get<dynamic>(
              '/albums/${Uri.encodeComponent(id)}/moments',
              queryParameters: {
                'limit': ?limit,
                if (cursor != null) ...{
                  'cursor.memoryTime': cursor.memoryTime,
                  'cursor.createdAt': cursor.createdAt,
                  'cursor.id': cursor.id,
                },
              },
            ),
          )
          as Map<String, dynamic>;

  Future<Map<String, dynamic>> update(
    String id,
    Map<String, dynamic> payload,
  ) async =>
      unwrap(
            await _dio.put<dynamic>(
              '/albums/${Uri.encodeComponent(id)}',
              data: payload,
            ),
          )
          as Map<String, dynamic>;

  Future<void> delete(String id) async {
    unwrap(await _dio.delete<dynamic>('/albums/${Uri.encodeComponent(id)}'));
  }

  Future<Map<String, dynamic>> create(Map<String, dynamic> payload) async =>
      unwrap(await _dio.post<dynamic>('/albums', data: payload))
          as Map<String, dynamic>;
  Future<Map<String, dynamic>> detail(String id) async =>
      unwrap(await _dio.get<dynamic>('/albums/${Uri.encodeComponent(id)}'))
          as Map<String, dynamic>;
}
