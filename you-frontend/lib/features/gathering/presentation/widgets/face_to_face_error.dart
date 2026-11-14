import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/errors/app_exception.dart';
import '../../../../core/location/device_location_service.dart';
import '../../../../core/network/api_client.dart';

/// 定位失败时提供对应设置入口；业务和网络失败展示统一提示。
class FaceToFaceError extends ConsumerWidget {
  const FaceToFaceError({super.key, required this.error});
  final Object error;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final code = error is AppException ? (error as AppException).code : null;
    final location = ref.read(deviceLocationProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          errorMessage(error),
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
        if (code == 'LOCATION_DISABLED')
          TextButton(
            onPressed: location.openLocationSettings,
            child: const Text('开启系统定位'),
          ),
        if (code == 'LOCATION_DENIED_FOREVER')
          TextButton(
            onPressed: location.openAppSettings,
            child: const Text('打开应用设置'),
          ),
      ],
    );
  }
}
