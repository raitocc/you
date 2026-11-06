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

  Future<String> login(String username, String password) =>
      _remote.login(username, password);

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

  Future<UserProfile> current() => AppLogger.track<UserProfile>(
    () async => userProfileFromJson(await _remote.current()),
    started: AppLogEvent.userProfileReadStarted,
    succeeded: AppLogEvent.userProfileReadSucceeded,
    failed: AppLogEvent.userProfileReadFailed,
  );

  Future<UserProfile> updateAvatar(String nickname, String fileId) async =>
      userProfileFromJson(await _remote.updateAvatar(nickname, fileId));

  final _local = ProfileLocalDataSource();

  Future<ProfileEdits> profileEdits(String userId) => _local.read(userId);

  Future<void> saveProfileEdits(String userId, ProfileEdits edits) =>
      _local.write(userId, edits);
}
