import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/errors/app_exception.dart';
import '../models/album.dart';
import 'album_inputs.dart';
import 'mappers/album_mapper.dart';
import 'remote/album_remote_data_source.dart';

final albumRepositoryProvider = Provider(
  (ref) => AlbumRepository(
    ref.watch(albumRemoteProvider),
    () => ref.read(albumInputsProvider),
  ),
);

class AlbumRepository {
  AlbumRepository(this._remote, this._inputs);
  final AlbumRemoteDataSource _remote;
  final AlbumInputs Function() _inputs;
  Future<AlbumPageData> browse({AlbumListCursor? cursor, int? limit}) async {
    final json = await _remote.browse(cursor: cursor, limit: limit);
    final next = json['nextCursor'] as Map<String, dynamic>?;
    return (
      items: List<Album>.unmodifiable(
        (json['items'] as List).cast<Map<String, dynamic>>().map(albumFromJson),
      ),
      nextCursor: next == null
          ? null
          : (createdAt: next['createdAt'] as String, id: next['id'] as String),
      hasMore: json['hasMore'] as bool,
    );
  }

  Future<AlbumTimelinePage> browseMoments(
    String id, {
    AlbumTimelineCursor? cursor,
    int? limit,
  }) async {
    final json = await _remote.browseMoments(id, cursor: cursor, limit: limit);
    final next = json['nextCursor'] as Map<String, dynamic>?;
    return (
      items: List<AlbumTimelineItem>.unmodifiable(
        (json['items'] as List).cast<Map<String, dynamic>>().map(
          albumTimelineItemFromJson,
        ),
      ),
      nextCursor: next == null
          ? null
          : (
              memoryTime: next['memoryTime'] as String,
              createdAt: next['createdAt'] as String,
              id: next['id'] as String,
            ),
      hasMore: json['hasMore'] as bool,
    );
  }

  Future<Album> detail(String id) async =>
      albumFromJson(await _remote.detail(id));
  Future<AlbumMomentPage> moments(AlbumCursor? cursor) =>
      _inputs().moments(cursor);
  Future<String> upload(Uint8List bytes, String mime) async {
    final inputs = _inputs();
    final id = await inputs.upload(bytes, mime);
    for (var attempt = 0; attempt < 10; attempt++) {
      if (await inputs.isReady(id)) return id;
      await Future<void>.delayed(const Duration(milliseconds: 600));
    }
    throw const AppException('UPLOAD_NOT_READY', '封面尚未就绪，请稍后重新选择');
  }

  Future<Album> addMoments(String id, List<String> momentIds) async =>
      albumFromJson(await _remote.addMoments(id, _selectedIds(momentIds)));

  Future<Album> removeMoments(String id, List<String> momentIds) async =>
      albumFromJson(await _remote.removeMoments(id, _selectedIds(momentIds)));

  List<String> _selectedIds(List<String> ids) {
    if (ids.isEmpty || ids.any((id) => id.trim().isEmpty)) {
      throw const AppException('INVALID_MOMENTS', '请至少选择一个有效瞬间');
    }
    return ids.toSet().toList(growable: false);
  }

  Future<void> delete(String id) => _remote.delete(id);

  Future<Album> update(
    String id, {
    required String name,
    required String? description,
    required String? coverFileId,
  }) async {
    final clean = name.trim();
    if (clean.isEmpty || clean.runes.length > 128) {
      throw const AppException('INVALID_NAME', '请填写 1～128 字符的时光名称');
    }
    if ((description?.runes.length ?? 0) > 5000) {
      throw const AppException('INVALID_DESCRIPTION', '介绍不能超过 5000 字符');
    }
    return albumFromJson(
      await _remote.update(id, {
        'name': clean,
        'description': description == '' ? null : description,
        'coverFileId': coverFileId,
      }),
    );
  }

  Future<Album> create({
    required String name,
    required String description,
    String? coverFileId,
    required List<String> momentIds,
  }) async {
    final clean = name.trim();
    if (clean.isEmpty || clean.runes.length > 128) {
      throw const AppException('INVALID_NAME', '请填写 1～128 字符的时光名称');
    }
    if (description.runes.length > 5000) {
      throw const AppException('INVALID_DESCRIPTION', '介绍不能超过 5000 字符');
    }
    return albumFromJson(
      await _remote.create({
        'name': clean,
        'description': description.isEmpty ? null : description,
        'coverFileId': coverFileId,
        'momentIds': momentIds.toSet().toList(),
      }),
    );
  }
}
