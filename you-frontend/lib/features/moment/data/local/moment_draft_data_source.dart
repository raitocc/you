import 'dart:convert';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';

import '../../../../core/storage/app_database.dart';
import '../../models/moment.dart';
import '../../models/moment_draft.dart';
import '../mappers/moment_draft_mapper.dart';

final momentDraftDataSourceProvider = Provider(
  (ref) => MomentDraftDataSource(ref.watch(databaseProvider)),
);

/// 草稿内容存入 Drift，原始图片复制到应用私有目录。
class MomentDraftDataSource {
  MomentDraftDataSource(this._database);
  final AppDatabase _database;

  Future<MomentDraft?> read(String owner, String key, Moment? current) async {
    final query = _database.select(_database.drafts)
      ..where((row) => row.ownerId.equals(owner) & row.draftId.equals(key));
    final draft = await query.getSingleOrNull();
    if (draft == null) return null;
    return draftFromJson(
      jsonDecode(draft.payload) as Map<String, dynamic>,
      current,
    );
  }

  Future<void> save(String owner, String key, MomentDraft draft) =>
      _database.saveDraft(owner, key, jsonEncode(draftToJson(draft)));

  Future<String> saveImage(
    String owner,
    String draftId,
    String imageId,
    Uint8List bytes,
  ) async {
    final directory = await _directory(owner, draftId);
    await directory.create(recursive: true);
    return (await File(
      '${directory.path}/$imageId',
    ).writeAsBytes(bytes, flush: true)).path;
  }

  Future<void> remove(String owner, String key, String draftId) async {
    await (_database.delete(_database.drafts)
          ..where((row) => row.ownerId.equals(owner) & row.draftId.equals(key)))
        .go();
    final directory = await _directory(owner, draftId);
    if (await directory.exists()) await directory.delete(recursive: true);
  }

  Future<Directory> _directory(String owner, String draftId) async {
    final root = await getApplicationSupportDirectory();
    return Directory(
      '${root.path}/moment-drafts/${Uri.encodeComponent(owner)}/${Uri.encodeComponent(draftId)}',
    );
  }
}
