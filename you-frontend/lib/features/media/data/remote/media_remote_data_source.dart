import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/network/api_client.dart';

final mediaRemoteDataSourceProvider = Provider(MediaRemoteDataSource.new);

class MediaRemoteDataSource {
  MediaRemoteDataSource(this._ref);

  final Ref _ref;

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

  Future<bool> isUploadReady(String fileId) async {
    final response = await _ref
        .read(dioProvider)
        .get<dynamic>('/media/uploads/${Uri.encodeComponent(fileId)}');
    return (unwrap(response) as Map<String, dynamic>)['ready'] as bool;
  }

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
