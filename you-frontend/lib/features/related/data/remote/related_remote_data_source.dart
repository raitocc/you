import 'package:dio/dio.dart';
import '../../../../core/network/api_client.dart';
import '../../models/related_entry.dart';

/// Related HTTP 请求复用统一 Dio 认证、错误响应和连接配置。
class RelatedRemoteDataSource {
  RelatedRemoteDataSource(this._dio);
  final Dio _dio;

  /// 首屏省略游标，后续展开分享时间和信箱动态 ID。
  Future<Map<String, dynamic>> browse({
    RelatedCursor? cursor,
    int? limit,
  }) async {
    final response = await _dio.get<dynamic>(
      '/moments/related',
      queryParameters: {
        if (cursor != null) ...{
          'cursor.sharedAt': cursor.sharedAt,
          'cursor.id': cursor.id,
        },
        'limit': ?limit,
      },
    );
    return unwrap(response) as Map<String, dynamic>;
  }
}
