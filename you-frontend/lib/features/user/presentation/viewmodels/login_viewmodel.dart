import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/auth/session.dart';
import '../../../../core/logging/app_logger.dart';
import '../../data/user_repository.dart';

// ViewModel 层：处理业务逻辑、管理状态、与 Repository 层交互。
final loginProvider = AsyncNotifierProvider.autoDispose<LoginViewModel, void>(
  LoginViewModel.new,
);

class LoginViewModel extends AsyncNotifier<void> {
  @override
  void build() {}
  /// 验证账号密码并建立本地登录会话。
  Future<void> submit(String username, String password) async {
    if (state.isLoading) return;
    state = const AsyncLoading();
    AppLogger.debug(AppLogEvent.authLoginStarted);
    final result = await AsyncValue.guard(() async {
      final token = await ref
          .read(userRepositoryProvider)
          .login(username, password);
      await ref.read(sessionProvider.notifier).setToken(token);
    });
    if (result case AsyncError(:final error, :final stackTrace)) {
      AppLogger.error(
        AppLogEvent.authLoginFailed,
        error: error,
        stackTrace: stackTrace,
      );
    } else {
      AppLogger.info(AppLogEvent.authLoginSucceeded);
    }
    if (ref.mounted) state = result;
  }
}
