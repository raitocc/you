import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';
import '../logging/app_logger.dart';
import 'package:geolocator/geolocator.dart';
import '../errors/app_exception.dart';

typedef DeviceCoordinates = ({double latitude, double longitude});
final deviceLocationProvider = Provider((ref) => DeviceLocationService());

/// 单次前台定位：检查系统开关、申请权限，再读取当前位置。
/// 保持设备坐标原值，发起和查询双方使用同一坐标系。
class DeviceLocationService {
  /// 包住整个原生调用阶段，使插件加载、权限检查和定位失败均可追踪。
  Future<DeviceCoordinates> current() async {
    try {
      return await AppLogger.track(
        _readCurrent,
        started: AppLogEvent.locationReadStarted,
        succeeded: AppLogEvent.locationReadSucceeded,
        failed: AppLogEvent.locationReadFailed,
      );
    } on MissingPluginException {
      throw const AppException(
        'LOCATION_PLUGIN_NOT_READY',
        '定位组件加载失败，请在 IDEA 停止应用后完整重新运行。',
      );
    } on PermissionDefinitionsNotFoundException {
      throw const AppException(
        'LOCATION_PERMISSION_CONFIG',
        '安装包缺少定位权限配置，请在 IDEA 完整重新运行应用。',
      );
    }
  }

  Future<DeviceCoordinates> _readCurrent() async {
    if (!await Geolocator.isLocationServiceEnabled()) {
      throw const AppException('LOCATION_DISABLED', '请开启系统定位，再重试。');
    }
    await _requestPermission();
    try {
      final position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.high,
          timeLimit: Duration(seconds: 20),
        ),
      );
      return (latitude: position.latitude, longitude: position.longitude);
    } on TimeoutException {
      throw const AppException('LOCATION_TIMEOUT', '定位超时，请到信号良好的位置重试。');
    }
  }

  Future<void> _requestPermission() async {
    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }
    if (permission == LocationPermission.deniedForever) {
      throw const AppException('LOCATION_DENIED_FOREVER', '请在应用设置中授权定位，再返回重试。');
    }
    if (permission == LocationPermission.denied) {
      throw const AppException('LOCATION_DENIED', '面对面加入需要定位权限，请授权后重试。');
    }
  }

  Future<bool> openAppSettings() => Geolocator.openAppSettings();
  Future<bool> openLocationSettings() => Geolocator.openLocationSettings();
}
