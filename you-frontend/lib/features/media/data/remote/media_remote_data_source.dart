import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/network/api_client.dart';

final mediaRemoteDataSourceProvider = Provider(MediaRemoteDataSource.new);

class MediaRemoteDataSource {
  MediaRemoteDataSource(this._ref);

  final Ref _ref;

  /// 向后端申请预签名地址，再将原始字节直接 PUT 到对象存储。
  Future<String> upload(
    Uint8List bytes,
    String mimeType, {
    void Function(int, int)? onProgress,
  }) async {
    final storage = _ref.read(storageDioProvider);
    final response = await _ref
        .read(dioProvider)
        .post<dynamic>(
          '/media/uploads',
          data: {'mimeType': mimeType, 'byteSize': bytes.length},
        );
    final ticket = unwrap(response) as Map<String, dynamic>;
    await storage.put<void>(
      ticket['url'] as String,
      data: Stream.value(bytes),
      options: Options(
        contentType: mimeType,
        headers: {Headers.contentLengthHeader: bytes.length},
        followRedirects: false,
      ),
      onSendProgress: onProgress,
    );
    return ticket['fileId'] as String;
  }

  /// 通过业务接口查询上传文件是否已完成登记。
  Future<bool> isUploadReady(String fileId) async {
    final response = await _ref
        .read(dioProvider)
        .get<dynamic>('/media/uploads/${Uri.encodeComponent(fileId)}');
    return (unwrap(response) as Map<String, dynamic>)['ready'] as bool;
  }

  /// 从对象存储的临时地址读取文件字节。
  Future<Uint8List> download(String url) async {
    final response = await _ref
        .read(storageDioProvider)
        .get<List<int>>(
          url,
          options: Options(
            responseType: ResponseType.bytes,
            followRedirects: false,
          ),
        );
    return Uint8List.fromList(response.data!);
  }
}
