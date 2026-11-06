import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/auth/session.dart';
import '../../../../core/errors/app_exception.dart';
import '../../../../core/logging/app_logger.dart';
import '../../../media/data/media_repository.dart';
import '../../data/user_repository.dart';
import '../../models/profile_edits.dart';
import '../../models/user_profile.dart';

typedef ProfileCardData = ({UserProfile user, ProfileEdits edits});

final profileProvider =
    AsyncNotifierProvider.autoDispose<ProfileViewModel, ProfileCardData>(
      ProfileViewModel.new,
    );

final profileAvatarProvider = FutureProvider.autoDispose<Uint8List?>((
  ref,
) async {
  final source = ref.watch(
    profileProvider.select((profile) {
      final data = profile.isLoading ? null : profile.asData?.value;
      return (
        fileId: data?.user.avatarFileId,
        url: data?.user.avatarUrl,
        local: data?.user.avatarFileId == null ? data?.edits.avatar : null,
      );
    }),
  );
  final url = source.url;
  if (url == null) return source.local;

  final media = ref.read(mediaRepositoryProvider);
  try {
    return await media.download(url);
  } on DioException catch (error) {
    if (error.response?.statusCode != 403 || !ref.mounted) rethrow;
    final user = await ref.read(userRepositoryProvider).current();
    final renewedUrl = user.avatarUrl;
    if (!ref.mounted ||
        renewedUrl == null ||
        user.avatarFileId != source.fileId) {
      return null;
    }
    return media.download(renewedUrl);
  }
}, retry: (_, _) => null);

class ProfileViewModel extends AsyncNotifier<ProfileCardData> {
  @override
  Future<ProfileCardData> build() async {
    ref.watch(sessionProvider);
    final repository = ref.read(userRepositoryProvider);
    final user = await repository.current();
    return (user: user, edits: await repository.profileEdits(user.id));
  }

  Future<void> save({
    String? nickname,
    String? signature,
    ({Uint8List bytes, String mimeType})? avatar,
    Uint8List? background,
  }) async {
    final current = state.requireValue;
    final session = ref.read(sessionProvider).asData?.value;
    final repository = ref.read(userRepositoryProvider);
    bool isCurrentSession() =>
        ref.mounted && ref.read(sessionProvider).asData?.value == session;

    var user = current.user;
    if (avatar != null) {
      final media = ref.read(mediaRepositoryProvider);
      final fileId = await media.upload(avatar.bytes, avatar.mimeType);
      var ready = false;
      for (var attempt = 0; attempt < 20 && isCurrentSession(); attempt++) {
        ready = await media.isUploadReady(fileId);
        if (ready) break;
        await Future<void>.delayed(const Duration(milliseconds: 500));
      }
      if (!isCurrentSession()) return;
      if (!ready) {
        throw const AppException('UPLOAD_NOT_READY', '图片还未登记，请稍后重试');
      }
      user = await repository.updateAvatar(user.nickname, fileId);
      if (!isCurrentSession()) return;
      state = AsyncData((user: user, edits: current.edits));
    }

    if (nickname == null && signature == null && background == null) return;
    final edits = (
      nickname: nickname ?? current.edits.nickname,
      signature: signature ?? current.edits.signature,
      avatar: current.edits.avatar,
      background: background ?? current.edits.background,
    );
    await repository.saveProfileEdits(current.user.id, edits);
    if (isCurrentSession()) {
      state = AsyncData((user: user, edits: edits));
    }
  }

  Future<void> logout() => AppLogger.track<void>(
    () => ref.read(sessionProvider.notifier).clear(),
    started: AppLogEvent.authLogoutStarted,
    succeeded: AppLogEvent.authLogoutSucceeded,
    failed: AppLogEvent.authLogoutFailed,
  );
}
