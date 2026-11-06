import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/errors/app_exception.dart';
import '../models/moment.dart';
import '../models/moment_draft.dart';
import 'local/moment_draft_data_source.dart';
import 'mappers/moment_draft_mapper.dart';
import 'mappers/moment_mapper.dart';
import 'remote/moment_remote_data_source.dart';

final momentRepositoryProvider = Provider(
  (ref) => MomentRepository(
    ref.watch(momentRemoteDataSourceProvider),
    ref.watch(momentDraftDataSourceProvider),
  ),
);

/// 跨页面变更只在当前会话内生效。
final momentRevisionProvider = NotifierProvider<MomentRevision, int>(
  MomentRevision.new,
);

class MomentRevision extends Notifier<int> {
  @override
  int build() => 0;

  void changed() => state++;
}

class MomentRepository {
  MomentRepository(this._remote, this._local);

  final MomentRemoteDataSource _remote;
  final MomentDraftDataSource _local;

  Future<MomentDraft?> readDraft(String owner, String key, Moment? current) =>
      _local.read(owner, key, current);

  Future<void> saveDraft(String owner, String key, MomentDraft draft) =>
      _local.save(owner, key, draft);

  Future<void> removeDraft(String owner, String key, String id) =>
      _local.remove(owner, key, id);

  Future<String> saveDraftImage(
    String owner,
    String draftId,
    String imageId,
    Uint8List bytes,
  ) => _local.saveImage(owner, draftId, imageId, bytes);

  Future<Uint8List> readDraftImage(String path) => File(path).readAsBytes();

  MomentDraft editDraft(Moment moment) => draftFromMoment(moment);

  MomentDraft preparePublication(MomentDraft draft) =>
      draft.pending == null ? draft.withPending(draftPayload(draft)) : draft;

  Future<MomentPageData> browse(MomentTab tab, [String? cursor]) async =>
      momentPageFromJson(await _remote.browse(tab.name, cursor));

  Future<Moment> detail(String id) async =>
      momentFromJson(await _remote.detail(id));

  Future<Moment?> recoverPublication(String id) async {
    try {
      return await detail(id);
    } on DioException catch (error) {
      if (error.response?.statusCode == 404) return null;
      rethrow;
    }
  }

  Future<Moment> publish(MomentDraft draft) async =>
      momentFromJson(await _remote.publish(draft.pending!));

  Future<Moment> edit(MomentDraft draft) async => momentFromJson(
    await _remote.edit(draft.id, draft.version!, draftPayload(draft)),
  );

  Future<void> delete(String id, String version) => _remote.delete(id, version);
}

String? momentErrorCode(Object error) {
  final cause = error is DioException ? error.error : error;
  return cause is AppException ? cause.code : null;
}

bool isDefinitiveMomentFailure(Object error) {
  final status = error is DioException ? error.response?.statusCode : null;
  return status != null && status >= 400 && status < 500 && status != 408;
}
