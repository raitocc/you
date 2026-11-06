import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/logging/app_logger.dart';
import '../models/profile_edits.dart';
import '../models/user_profile.dart';
import 'local/profile_local_data_source.dart';
import 'mappers/user_profile_mapper.dart';
import 'remote/user_remote_data_source.dart';

final userRepositoryProvider = Provider(
  (ref) => UserRepository(ref.watch(userRemoteDataSourceProvider)),
);

class UserRepository {
  UserRepository(this._remote);

  final UserRemoteDataSource _remote;

  /// 登录并返回会话令牌。
  Future<String> login(String username, String password) =>
      _remote.login(username, password);

  /// 注册账号并转换返回的用户资料。
  Future<UserProfile> register(
    String username,
    String password,
    String nickname,
  ) => AppLogger.track<UserProfile>(
    () async => userProfileFromJson(
      await _remote.register(username, password, nickname),
    ),
    started: AppLogEvent.authRegisterStarted,
    succeeded: AppLogEvent.authRegisterSucceeded,
    failed: AppLogEvent.authRegisterFailed,
  );

  /// 读取当前登录用户的服务端资料。
  Future<UserProfile> current() => AppLogger.track<UserProfile>(
    () async => userProfileFromJson(await _remote.current()),
    started: AppLogEvent.userProfileReadStarted,
    succeeded: AppLogEvent.userProfileReadSucceeded,
    failed: AppLogEvent.userProfileReadFailed,
  );

  /// 用已登记的媒体文件更新用户头像。
  Future<UserProfile> updateAvatar(String nickname, String fileId) async =>
      userProfileFromJson(await _remote.updateAvatar(nickname, fileId));

  final _local = ProfileLocalDataSource();

  /// 读取指定账号的本地资料编辑。
  Future<ProfileEdits> profileEdits(String userId) => _local.read(userId);

  /// 保存指定账号的本地资料编辑。
  Future<void> saveProfileEdits(String userId, ProfileEdits edits) =>
      _local.write(userId, edits);
}
