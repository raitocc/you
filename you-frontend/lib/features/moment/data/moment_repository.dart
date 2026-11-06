import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/errors/app_exception.dart';
import '../../user/data/user_repository.dart';
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
    ref.watch(userRepositoryProvider),
  ),
);

/// 跨页面变更只在当前会话内生效。
final momentRevisionProvider = NotifierProvider<MomentRevision, int>(
  MomentRevision.new,
);

class MomentRevision extends Notifier<int> {
  @override
  int build() => 0;

  /// 通知当前会话内的页面刷新。
  void changed() => state++;
}

class MomentRepository {
  MomentRepository(this._remote, this._local, this._users);

  final MomentRemoteDataSource _remote;
  final MomentDraftDataSource _local;
  final UserRepository _users;

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

  /// 从远端瞬间创建编辑草稿。
  MomentDraft editDraft(Moment moment) => draftFromMoment(moment);

  /// 固定待发布的请求内容，供失败重试时复用。
  MomentDraft preparePublication(MomentDraft draft) =>
      draft.withPending(createMomentPayload(draft));

  /// 读取本人瞬间卡片及分页信息。
  Future<MomentPageData> browseYours([String? cursor]) async =>
      momentPageFromJson(await _remote.browseYours(cursor));

  /// 查询本人瞬间，使用当前账号资料补充作者信息。
  Future<Moment> detail(String id) async {
    final response = await _remote.detail(id);
    return momentFromJson(response, currentAuthor: await _currentAuthor());
  }

  /// 按草稿 ID 查询上次发布结果，详情不存在时返回空值。
  Future<Moment?> recoverPublication(String id) async {
    try {
      return await detail(id);
    } on DioException catch (error) {
      if (error.response?.statusCode == 404) return null;
      rethrow;
    }
  }

  /// 发送已保存的发布请求并解析返回的详情。
  Future<Moment> publish(MomentDraft draft) async {
    final author = await _currentAuthor();
    return momentFromJson(
      await _remote.publish(draft.pending!),
      currentAuthor: author,
    );
  }

  /// 全量保存编辑内容，并按本人详情契约解析结果。
  Future<Moment> edit(MomentDraft draft) async {
    final author = await _currentAuthor();
    return momentFromJson(
      await _remote.edit(draft.id, updateMomentPayload(draft)),
      currentAuthor: author,
    );
  }

  /// 删除本人瞬间。
  Future<void> delete(String id) => _remote.delete(id);

  /// 获取本人详情和发布结果使用的作者信息。
  Future<MomentAuthor> _currentAuthor() async {
    final user = await _users.current();
    return (id: user.id, nickname: user.nickname, avatarUrl: user.avatarUrl);
  }
}

String? momentErrorCode(Object error) {
  final cause = error is DioException ? error.error : error;
  return cause is AppException ? cause.code : null;
}

/// 判断发布失败后是否可以解除草稿的待确认状态。
bool isDefinitiveMomentFailure(Object error) {
  final status = error is DioException ? error.response?.statusCode : null;
  return status != null && status >= 400 && status < 500 && status != 408;
}
