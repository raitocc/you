import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../logging/app_logger.dart';

final secureStorageProvider = Provider((ref) => const FlutterSecureStorage());
final sessionProvider = AsyncNotifierProvider<SessionController, String?>(
  SessionController.new,
);

class SessionController extends AsyncNotifier<String?> {
  @override
  Future<String?> build() => AppLogger.track<String?>(
    () => ref.read(secureStorageProvider).read(key: 'access_token'),
    started: AppLogEvent.authSessionRestoreStarted,
    succeeded: AppLogEvent.authSessionRestoreSucceeded,
    failed: AppLogEvent.authSessionRestoreFailed,
  );
  Future<void> setToken(String token) => AppLogger.track<void>(
    () async {
      await ref
          .read(secureStorageProvider)
          .write(key: 'access_token', value: token);
      state = AsyncData(token);
    },
    started: AppLogEvent.authSessionPersistStarted,
    succeeded: AppLogEvent.authSessionPersistSucceeded,
    failed: AppLogEvent.authSessionPersistFailed,
  );

  Future<void> clear() => AppLogger.track<void>(
    () async {
      state = const AsyncData(null);
      await ref.read(secureStorageProvider).delete(key: 'access_token');
    },
    started: AppLogEvent.authSessionClearStarted,
    succeeded: AppLogEvent.authSessionClearSucceeded,
    failed: AppLogEvent.authSessionClearFailed,
  );
}
