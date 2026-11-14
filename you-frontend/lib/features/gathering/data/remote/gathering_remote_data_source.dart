import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/network/api_client.dart';
import '../../models/gathering.dart';
import '../../../../core/location/device_location_service.dart';
import '../../../moment/models/moment.dart';

final gatheringRemoteProvider = Provider(
  (ref) => GatheringRemoteDataSource(ref.watch(dioProvider)),
);

/// 同行 HTTP 数据源，认证与错误转换沿用公共 Dio 客户端。
class GatheringRemoteDataSource {
  GatheringRemoteDataSource(this._dio);

  final Dio _dio;

  /// 首次开启：短码为字符串，坐标为本次设备定位结果。
  Future<Map<String, dynamic>> openFaceToFace(
    String id,
    String code,
    DeviceCoordinates location,
  ) async {
    final response = await _dio.post<dynamic>(
      '/gatherings/${Uri.encodeComponent(id)}/face-to-face',
      data: {
        'faceCode': code,
        'latitude': location.latitude,
        'longitude': location.longitude,
      },
    );
    return unwrap(response) as Map<String, dynamic>;
  }

  /// 预览返回全部附近候选，空列表也作为正常结果。
  Future<List<dynamic>> previewFaceToFace(
    String code,
    DeviceCoordinates location,
  ) async {
    final response = await _dio.get<dynamic>(
      '/gatherings/face-to-face/join-preview',
      queryParameters: {
        'faceCode': code,
        'latitude': location.latitude,
        'longitude': location.longitude,
      },
    );
    return unwrap(response) as List<dynamic>;
  }

  /// 确认只传已选择的同行 ID 和短码，服务端校验有效期并建立成员关系。
  Future<Map<String, dynamic>> joinFaceToFace(String id, String code) async {
    final response = await _dio.post<dynamic>(
      '/gatherings/face-to-face/join',
      data: {'gatheringId': id, 'faceCode': code},
    );
    return unwrap(response) as Map<String, dynamic>;
  }

  /// 普通成员退出，服务端解除其瞬间关联。
  Future<void> leave(String id) async {
    unwrap(
      await _dio.post<dynamic>('/gatherings/${Uri.encodeComponent(id)}/leave'),
    );
  }

  /// 创建者结束同行，服务端保留内容并关闭加入与新增关联。
  Future<void> end(String id) async {
    unwrap(
      await _dio.post<dynamic>('/gatherings/${Uri.encodeComponent(id)}/end'),
    );
  }

  /// 创建者解散同行，成功响应的 data 为 null。
  Future<void> dissolve(String id) async {
    unwrap(
      await _dio.delete<dynamic>('/gatherings/${Uri.encodeComponent(id)}'),
    );
  }

  /// 时间线按服务端正序浏览，完整保留发生时间与 ID 游标。
  Future<Map<String, dynamic>> moments(
    String id, {
    MomentFeedCursor? cursor,
    int? limit,
  }) async {
    final response = await _dio.get<dynamic>(
      '/gatherings/${Uri.encodeComponent(id)}/moments',
      queryParameters: {
        'limit': ?limit,
        if (cursor != null) ...{
          'cursor.memoryTime': cursor.memoryTime,
          'cursor.id': cursor.id,
        },
      },
    );
    return unwrap(response) as Map<String, dynamic>;
  }

  /// 同行详情按共享路径授权，与本人详情请求保持独立。
  Future<Map<String, dynamic>> moment(String id, String momentId) async {
    final response = await _dio.get<dynamic>(
      '/gatherings/${Uri.encodeComponent(id)}/moments/${Uri.encodeComponent(momentId)}',
    );
    return unwrap(response) as Map<String, dynamic>;
  }

  /// 批量关联或移出，后端整批校验并维护原瞬间上的同行关系。
  Future<void> changeMoments(
    String id,
    List<String> momentIds, {
    required bool removing,
  }) async {
    final suffix = removing ? '/remove' : '';
    final response = await _dio.post<dynamic>(
      '/gatherings/${Uri.encodeComponent(id)}/moments$suffix',
      data: {'momentIds': momentIds},
    );
    unwrap(response);
  }

  /// 邀请码仅用于查询加入目标，预览本身保持成员关系不变。
  Future<Map<String, dynamic>> previewJoin(String inviteCode) async {
    final response = await _dio.get<dynamic>(
      '/gatherings/join-preview',
      queryParameters: {'inviteCode': inviteCode},
    );
    return unwrap(response) as Map<String, dynamic>;
  }

  /// 确认加入，由后端再次校验邀请码和同行的可加入状态。
  Future<Map<String, dynamic>> join(String inviteCode) async {
    final response = await _dio.post<dynamic>(
      '/gatherings/join',
      data: {'inviteCode': inviteCode},
    );
    return unwrap(response) as Map<String, dynamic>;
  }

  /// 首屏省略游标，后续页提交完整的时间与 ID。
  Future<Map<String, dynamic>> browse({
    GatheringCursor? cursor,
    int? limit,
  }) async {
    final response = await _dio.get<dynamic>(
      '/gatherings',
      queryParameters: {
        'limit': ?limit,
        if (cursor != null) ...{
          'cursor.createdAt': cursor.createdAt,
          'cursor.id': cursor.id,
        },
      },
    );
    return unwrap(response) as Map<String, dynamic>;
  }

  /// 创建同行，介绍为空时显式传 null。
  Future<Map<String, dynamic>> create({
    required String name,
    required String? description,
  }) async {
    final response = await _dio.post<dynamic>(
      '/gatherings',
      data: {'name': name, 'description': description},
    );
    return unwrap(response) as Map<String, dynamic>;
  }

  /// 成员查看指定同行的基本信息。
  Future<Map<String, dynamic>> detail(String id) async {
    final response = await _dio.get<dynamic>(
      '/gatherings/${Uri.encodeComponent(id)}',
    );
    return unwrap(response) as Map<String, dynamic>;
  }

  /// 全量更新名称和介绍，description 显式传 null 表示清空。
  Future<Map<String, dynamic>> update(
    String id, {
    required String name,
    required String? description,
  }) async {
    final response = await _dio.put<dynamic>(
      '/gatherings/${Uri.encodeComponent(id)}',
      data: {'name': name, 'description': description},
    );
    return unwrap(response) as Map<String, dynamic>;
  }

  /// 分页读取公开资料；游标采用成员接口的 userId 字段。
  Future<Map<String, dynamic>> members(
    String id, {
    GatheringMemberCursor? cursor,
    int? limit,
  }) async {
    final response = await _dio.get<dynamic>(
      '/gatherings/${Uri.encodeComponent(id)}/members',
      queryParameters: {
        'limit': ?limit,
        if (cursor != null) ...{
          'cursor.createdAt': cursor.createdAt,
          'cursor.userId': cursor.userId,
        },
      },
    );
    return unwrap(response) as Map<String, dynamic>;
  }
}
