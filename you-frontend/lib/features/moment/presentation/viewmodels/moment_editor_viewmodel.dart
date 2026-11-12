import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../shared/models/uuid_v7.dart';

import '../../../../core/auth/session.dart';
import '../../../../core/errors/app_exception.dart';
import '../../../media/data/media_repository.dart';
import '../../../user/data/user_repository.dart';
import '../../data/moment_repository.dart';
import '../../models/moment.dart';
import '../../models/moment_draft.dart';
import '../states/moment_editor_state.dart';

final momentEditorProvider = AsyncNotifierProvider.autoDispose
    .family<MomentEditorViewModel, MomentEditorState, String?>(
      MomentEditorViewModel.new,
    );

class MomentEditorViewModel extends AsyncNotifier<MomentEditorState> {
  MomentEditorViewModel(this.id);
  final String? id;

  String get _key => 'moment:${id ?? 'new'}';
  String? _owner;

  /// 按当前账号恢复草稿，编辑时同时读取远端版本并检查权限。
  @override
  Future<MomentEditorState> build() async {
    final token = ref.watch(sessionProvider).asData?.value;
    if (token == null) throw const AppException('AUTH_REQUIRED', '请先登录');
    final users = ref.watch(userRepositoryProvider);
    final repository = ref.watch(momentRepositoryProvider);
    final owner = (await users.current()).id;
    _checkSession(token);
    _owner = owner;
    final original = id == null ? null : await repository.detail(id!);
    _checkSession(token);
    if (original != null && !original.canEdit) {
      throw const AppException('FORBIDDEN', '你没有编辑权限');
    }
    final saved = await repository.readDraft(owner, _key, original);
    _checkSession(token);
    final draft =
        saved ??
        (original == null
            ? MomentDraft(id: uuidV7())
            : repository.editDraft(original));
    return (
      draft: draft,
      original: original,
      dirty: saved != null,
      progress: null,
      error: null,
    );
  }

  /// 更新可编辑草稿，并保留尚未处理的版本冲突。
  void updateDraft(MomentDraft Function(MomentDraft) change) {
    final current = state.asData?.value;
    if (current == null ||
        current.progress != null ||
        current.draft.pending != null) {
      return;
    }
    _set(
      current,
      change(current.draft),
      dirty: true,
      error:
          current.error != null &&
              momentErrorCode(current.error!) == 'VERSION_CONFLICT'
          ? current.error
          : null,
    );
  }

  /// 校验图片数量和格式，将原图保存到本地草稿目录。
  Future<void> addImages(List<Uint8List> files) async {
    final current = state.asData?.value;
    final owner = _owner;
    if (current == null ||
        owner == null ||
        current.progress != null ||
        current.draft.pending != null) {
      return;
    }
    final token = ref.read(sessionProvider).asData?.value;
    var draft = current.draft;
    _set(current, draft, progress: '正在添加照片');
    try {
      if (draft.images.length + files.length > 20) {
        throw const AppException('TOO_MANY_IMAGES', '一个瞬间最多添加 20 张照片');
      }
      final added = <DraftImage>[];
      for (final bytes in files) {
        _checkSession(token);
        _imageType(bytes);
        final path = await ref
            .read(momentRepositoryProvider)
            .saveDraftImage(owner, draft.id, uuidV7(), bytes);
        added.add(DraftImage(localPath: path));
      }
      _checkSession(token);
      draft = draft.withImages(List.unmodifiable([...draft.images, ...added]));
      _set(current, draft, dirty: true);
    } catch (error) {
      if (_isCurrent(token)) _set(current, draft, error: error);
    }
  }

  /// 保存当前编辑内容供下次恢复。
  Future<void> saveDraft() async {
    final current = state.requireValue;
    await ref
        .read(momentRepositoryProvider)
        .saveDraft(_owner!, _key, current.draft);
  }

  /// 清理当前账号的草稿及原图。
  Future<void> discard() async {
    final current = state.requireValue;
    await ref
        .read(momentRepositoryProvider)
        .removeDraft(_owner!, _key, current.draft.id);
  }

  /// 用户核对远端变化后，可显式使用新版本再次提交本地草稿。
  Future<void> useLatestVersion() async {
    final current = state.requireValue;
    final token = ref.read(sessionProvider).asData?.value;
    _set(current, current.draft, progress: '正在获取最新版本');
    try {
      final latest = await ref.read(momentRepositoryProvider).detail(id!);
      _checkSession(token);
      if (!latest.canEdit) throw const AppException('FORBIDDEN', '你没有编辑权限');
      final old = current.draft;
      final draft = MomentDraft(
        id: old.id,
        version: latest.version,
        title: old.title,
        body: old.body,
        tags: old.tags,
        mood: old.mood,
        time: old.time,
        location: old.location,
        journeyId: old.journeyId,
        circleIds: old.circleIds,
        images: old.images,
      );
      _set(current, draft, dirty: true);
    } catch (error) {
      if (_isCurrent(token)) _set(current, current.draft, error: error);
    }
  }

  /// 恢复未确认的发布结果，或在图片就绪后发布或保存编辑。
  Future<Moment?> submit() async {
    final current = state.asData?.value;
    final owner = _owner;
    if (current == null || owner == null || current.progress != null) {
      return null;
    }
    final token = ref.read(sessionProvider).asData?.value;
    final repository = ref.read(momentRepositoryProvider);
    final media = ref.read(mediaRepositoryProvider);
    var draft = current.draft;
    _set(current, draft, progress: '正在保存');
    try {
      Moment? result;
      if (draft.pending != null) {
        result = await repository.recoverPublication(draft.id);
        _checkSession(token);
      }
      if (result == null &&
          (draft.journeyId != null || draft.circleIds.isNotEmpty)) {
        throw const AppException('UNSUPPORTED_SHARING', '当前仅支持私人瞬间，请先移除同行和圈子');
      }
      if (draft.pending == null) {
        _validate(draft);
        await repository.saveDraft(owner, _key, draft);
        for (var index = 0; index < draft.images.length; index++) {
          final image = draft.images[index];
          if (image.localPath == null) continue;
          _checkSession(token);
          _set(
            current,
            draft,
            progress: '正在上传 ${index + 1}/${draft.images.length}',
          );
          var fileId = image.fileId;
          if (fileId == null) {
            final bytes = await repository.readDraftImage(image.localPath!);
            _checkSession(token);
            fileId = await media.upload(bytes, _imageType(bytes));
            _checkSession(token);
            final images = [...draft.images];
            images[index] = DraftImage(
              fileId: fileId,
              localPath: image.localPath,
            );
            draft = draft.withImages(List.unmodifiable(images));
            await repository.saveDraft(owner, _key, draft);
          }
          var ready = false;
          for (var attempt = 0; attempt < 30; attempt++) {
            _checkSession(token);
            ready = await media.isUploadReady(fileId);
            if (ready) break;
            await Future<void>.delayed(const Duration(milliseconds: 600));
          }
          if (!ready) {
            throw const AppException('UPLOAD_NOT_READY', '图片还未就绪，请稍后重试');
          }
        }
      }

      if (result == null) {
        _checkSession(token);
        _set(current, draft, progress: id == null ? '正在发布' : '正在保存');
        if (id == null) {
          draft = repository.preparePublication(draft);
          await repository.saveDraft(owner, _key, draft);
          _checkSession(token);
          result = await repository.publish(draft);
        } else {
          result = await repository.edit(draft);
        }
      }
      _checkSession(token);
      await repository.removeDraft(owner, _key, draft.id);
      _checkSession(token);
      _set(current, draft.withPending(null), dirty: false);
      ref.read(momentRevisionProvider.notifier).changed();
      return result;
    } catch (error) {
      if (!_isCurrent(token)) return null;
      if (draft.pending != null &&
          (isDefinitiveMomentFailure(error) ||
              momentErrorCode(error) == 'UNSUPPORTED_SHARING')) {
        final editable = draft.withPending(null);
        try {
          await repository.saveDraft(owner, _key, editable);
          draft = editable;
        } catch (_) {
          // 保留已写入磁盘的未决请求。
        }
      }
      if (_isCurrent(token)) _set(current, draft, dirty: true, error: error);
      return null;
    }
  }

  void _set(
    MomentEditorState current,
    MomentDraft draft, {
    bool? dirty,
    String? progress,
    Object? error,
  }) {
    state = AsyncData((
      draft: draft,
      original: current.original,
      dirty: dirty ?? current.dirty,
      progress: progress,
      error: error,
    ));
  }

  bool _isCurrent(String? token) =>
      ref.mounted &&
      token != null &&
      ref.read(sessionProvider).asData?.value == token;

  /// 中止当前会话已变化的操作。
  void _checkSession(String? token) {
    if (!_isCurrent(token)) {
      throw const AppException('AUTH_REQUIRED', '登录状态已变化');
    }
  }
}

/// 校验草稿是否满足提交要求。
void _validate(MomentDraft draft) {
  if (draft.body.trim().isEmpty && draft.images.isEmpty) {
    throw const AppException('EMPTY_CONTENT', '请填写正文或添加图片');
  }
  if (utf8.encode(draft.body).length > 60000) {
    throw const AppException('INVALID_PARAMETER', '正文最多 60000 个 UTF-8 字节');
  }
  if (draft.tags.length > 20 ||
      draft.tags.any((tag) => tag.runes.length > 64) ||
      draft.tags.toSet().length != draft.tags.length) {
    throw const AppException('INVALID_PARAMETER', '最多填写 20 个不重复的标签，每个最多 64 个字');
  }
}

/// 根据文件头识别图片类型。
String _imageType(Uint8List bytes) {
  if (bytes.length > 10 * 1024 * 1024) {
    throw const AppException('INVALID_IMAGE', '每张图片不能超过 10 MiB');
  }
  bool matches(int offset, List<int> signature) =>
      bytes.length >= offset + signature.length &&
      List.generate(
        signature.length,
        (index) => bytes[index + offset] == signature[index],
      ).every((match) => match);
  if (matches(0, [0xff, 0xd8, 0xff])) return 'image/jpeg';
  if (matches(0, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) {
    return 'image/png';
  }
  if (matches(0, 'RIFF'.codeUnits) && matches(8, 'WEBP'.codeUnits)) {
    return 'image/webp';
  }
  throw const AppException('INVALID_IMAGE', '请选择 JPEG、PNG 或 WebP 图片');
}
