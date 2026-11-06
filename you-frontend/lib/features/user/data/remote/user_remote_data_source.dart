import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/network/api_client.dart';

final userRemoteDataSourceProvider = Provider(UserRemoteDataSource.new);

class UserRemoteDataSource {
  UserRemoteDataSource(this._ref);

  final Ref _ref;

  Dio get _api => _ref.read(dioProvider);

  /// 提交登录凭证并提取令牌。
  Future<String> login(String username, String password) async {
    final response = await _api.post<dynamic>(
      '/auth/login',
      data: {'username': username, 'password': password},
    );
    return (unwrap(response) as Map<String, dynamic>)['accessToken'] as String;
  }

  /// 提交注册信息并返回服务端资料。
  Future<Map<String, dynamic>> register(
    String username,
    String password,
    String nickname,
  ) async {
    final response = await _api.post<dynamic>(
      '/auth/register',
      data: {'username': username, 'password': password, 'nickname': nickname},
    );
    return unwrap(response) as Map<String, dynamic>;
  }

  /// 请求当前用户的资料。
  Future<Map<String, dynamic>> current() async {
    final response = await _api.get<dynamic>('/users/me');
    return unwrap(response) as Map<String, dynamic>;
  }

  /// 将已上传文件 ID 提交给头像更新接口。
  Future<Map<String, dynamic>> updateAvatar(
    String nickname,
    String fileId,
  ) async {
    final response = await _api.put<dynamic>(
      '/users/me',
      data: {'nickname': nickname, 'avatarFileId': fileId},
    );
    return unwrap(response) as Map<String, dynamic>;
  }
}
