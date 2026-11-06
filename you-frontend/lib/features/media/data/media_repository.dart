import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/logging/app_logger.dart';
import '../../../core/errors/app_exception.dart';

import 'remote/media_remote_data_source.dart';

final mediaRepositoryProvider = Provider(
  (ref) => MediaRepository(ref.watch(mediaRemoteDataSourceProvider)),
);

class MediaRepository {
  MediaRepository(this._remote);
  final MediaRemoteDataSource _remote;
  /// 校验图片格式和大小，上传后返回文件 ID。
  Future<String> upload(
    Uint8List bytes,
    String mimeType, {
    void Function(int, int)? onProgress,
  }) async {
    AppLogger.debug(AppLogEvent.storageUploadStarted);
    try {
      if (!['image/jpeg', 'image/png', 'image/webp'].contains(mimeType) ||
          bytes.isEmpty ||
          bytes.length > 10485760) {
        throw const AppException(
          'INVALID_IMAGE',
          '请选择 10 MiB 以内的 JPEG、PNG 或 WebP 图片',
        );
      }
      final fileId = await _remote.upload(
        bytes,
        mimeType,
        onProgress: onProgress,
      );
      AppLogger.info(AppLogEvent.storageUploadSucceeded);
      return fileId;
    } catch (error, stackTrace) {
      AppLogger.error(
        AppLogEvent.storageUploadFailed,
        error: error,
        stackTrace: stackTrace,
      );
      rethrow;
    }
  }

  /// 查询上传文件是否已登记，可供业务关联。
  Future<bool> isUploadReady(String fileId) => _remote.isUploadReady(fileId);

  /// 读取临时地址对应的媒体字节。
  Future<Uint8List> download(String url) => _remote.download(url);
}
