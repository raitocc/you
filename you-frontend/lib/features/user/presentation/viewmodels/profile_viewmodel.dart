import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/auth/session.dart';
import '../../../../core/logging/app_logger.dart';
import '../../data/user_repository.dart';
import '../../models/user_profile.dart';

final profileProvider =
    AsyncNotifierProvider.autoDispose<ProfileViewModel, UserProfile>(
      ProfileViewModel.new,
    );

class ProfileViewModel extends AsyncNotifier<UserProfile> {
  @override
  Future<UserProfile> build() async {
    ref.watch(sessionProvider);
    return ref.read(userRepositoryProvider).current();
  }

  Future<void> logout() => AppLogger.track<void>(
    () => ref.read(sessionProvider.notifier).clear(),
    started: AppLogEvent.authLogoutStarted,
    succeeded: AppLogEvent.authLogoutSucceeded,
    failed: AppLogEvent.authLogoutFailed,
  );
}
