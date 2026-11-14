import 'package:flutter_test/flutter_test.dart';
import 'package:you_app/core/errors/app_exception.dart';
import 'package:you_app/core/location/device_location_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('原生定位通道缺失时返回可操作的错误提示', () async {
    // 测试环境没有原生通道，与旧 APK 热更新 Dart 后出现的异常路径相同。
    await expectLater(
      DeviceLocationService().current(),
      throwsA(
        isA<AppException>()
            .having((e) => e.code, 'code', 'LOCATION_PLUGIN_NOT_READY')
            .having((e) => e.message, 'message', contains('完整重新运行')),
      ),
    );
  });
}
