import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/album.dart';

/// 跨模块能力由 app 组装层注入，业务模块不相互引用。
class AlbumInputs {
  const AlbumInputs({
    required this.moments,
    required this.upload,
    required this.isReady,
  });
  final Future<AlbumMomentPage> Function(AlbumCursor? cursor) moments;
  final Future<bool> Function(String id) isReady;
  final Future<String> Function(Uint8List bytes, String mimeType) upload;
}

final albumInputsProvider = Provider<AlbumInputs>(
  (ref) => throw StateError('Album inputs must be assembled by app'),
);
