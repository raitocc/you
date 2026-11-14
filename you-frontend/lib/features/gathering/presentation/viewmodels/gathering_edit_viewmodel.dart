import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/auth/session.dart';
import '../../../user/data/user_repository.dart';
import '../../data/gathering_repository.dart';
import '../../models/gathering.dart';
import 'gathering_detail_viewmodel.dart';
import 'gathering_list_viewmodel.dart';

/// 同行页面共用本人身份，切换账号时重新读取。
final gatheringCurrentUserIdProvider = FutureProvider.autoDispose<String>((
  ref,
) async {
  ref.watch(sessionProvider);
  final user = await ref.watch(userRepositoryProvider).current();
  return user.id;
}, retry: (_, _) => null);

/// 仅控制前端编辑入口，服务端继续执行创建者权限校验。
final gatheringCanEditProvider = FutureProvider.autoDispose
    .family<bool, String>((ref, id) async {
      final gatheringFuture = ref.watch(gatheringDetailProvider(id).future);
      final userIdFuture = ref.watch(gatheringCurrentUserIdProvider.future);
      final gathering = await gatheringFuture;
      final userId = await userIdFuture;
      return gathering.creator?.id == userId;
    }, retry: (_, _) => null);

final gatheringEditProvider = AsyncNotifierProvider.autoDispose
    .family<GatheringEditViewModel, void, String>(GatheringEditViewModel.new);

/// 每个同行独立管理保存状态，表单内容由页面保留。
class GatheringEditViewModel extends AsyncNotifier<void> {
  GatheringEditViewModel(this.id);
  final String id;

  @override
  void build() {
    ref.watch(sessionProvider);
  }

  /// 全量保存基本信息，成功后刷新详情和列表，失败时保留输入。
  Future<Gathering?> save(String name, String description) async {
    if (state.isLoading) return null;
    final token = ref.read(sessionProvider).asData?.value;
    state = const AsyncLoading();
    try {
      final gathering = await ref
          .read(gatheringRepositoryProvider)
          .update(
            id,
            name: name.trim(),
            description: description.trim().isEmpty ? null : description,
          );
      if (!_isCurrentSession(token)) return null;
      state = const AsyncData(null);
      ref.invalidate(gatheringDetailProvider(id));
      ref.invalidate(gatheringListProvider);
      return gathering;
    } catch (error, stackTrace) {
      if (_isCurrentSession(token)) state = AsyncError(error, stackTrace);
      return null;
    }
  }

  /// 旧页面或旧账号的请求结束后停止更新界面。
  bool _isCurrentSession(String? token) =>
      ref.mounted && ref.read(sessionProvider).asData?.value == token;
}
