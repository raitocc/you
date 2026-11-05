import 'dart:ui';

import 'package:flutter/widgets.dart';

import 'app/bootstrap.dart';
import 'core/logging/app_logger.dart';

Future<void> main() async {
  try {
    WidgetsFlutterBinding.ensureInitialized();

    final previousFlutterHandler = FlutterError.onError;
    FlutterError.onError = (details) {
      AppLogger.error(
        AppLogEvent.appFrameworkFailed,
        error: details.exception,
        stackTrace: details.stack,
      );
      previousFlutterHandler?.call(details);
    };

    final previousPlatformHandler = PlatformDispatcher.instance.onError;
    PlatformDispatcher.instance.onError = (error, stackTrace) {
      AppLogger.error(
        AppLogEvent.appRuntimeFailed,
        error: error,
        stackTrace: stackTrace,
      );
      return previousPlatformHandler?.call(error, stackTrace) ?? false;
    };

    await bootstrap();
    AppLogger.info(AppLogEvent.appInitializationSucceeded);
  } catch (error, stackTrace) {
    AppLogger.fatal(
      AppLogEvent.appInitializationFailed,
      error: error,
      stackTrace: stackTrace,
    );
    rethrow;
  }
}
