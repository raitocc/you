import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/network/api_client.dart';

final userRemoteDataSourceProvider = Provider(UserRemoteDataSource.new);

class UserRemoteDataSource {
  UserRemoteDataSource(this._ref);

  final Ref _ref;

  Dio get _api => _ref.read(dioProvider);

  Future<String> login(String username, String password) async {
    final response = await _api.post<dynamic>(
      '/auth/login',
      data: {'username': username, 'password': password},
    );
    return (unwrap(response) as Map<String, dynamic>)['accessToken'] as String;
  }

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

  Future<Map<String, dynamic>> current() async {
    final response = await _api.get<dynamic>('/users/me');
    return unwrap(response) as Map<String, dynamic>;
  }
}
