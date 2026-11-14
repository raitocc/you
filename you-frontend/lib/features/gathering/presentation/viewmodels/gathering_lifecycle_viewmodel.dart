import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/auth/session.dart';
import '../../../../shared/models/content_revision.dart';
import '../../data/gathering_repository.dart';
import '../../models/gathering_lifecycle_action.dart';
import 'gathering_members_viewmodel.dart';

final gatheringLifecycleProvider = AsyncNotifierProvider.autoDispose
    .family<GatheringLifecycleViewModel, void, String>(
      GatheringLifecycleViewModel.new,
    );

/// 详情页的退出、结束和解散共用提交状态，阻止重复提交与交叉操作。
class GatheringLifecycleViewModel extends AsyncNotifier<void> {
  GatheringLifecycleViewModel(this.id);
  final String id;

  @override
  void build() {
    ref.watch(sessionProvider);
  }

  /// 成功广播内容变化并刷新成员；失败保留当前页面，由调用方展示提示。
  Future<bool> execute(GatheringLifecycleAction action) async {
    if (state.isLoading) return false;
    final token = ref.read(sessionProvider).asData?.value;
    state = const AsyncLoading();
    try {
      await _perform(action);
      if (!_isCurrentSession(token)) return false;
      ref.read(momentRevisionProvider.notifier).changed();
      ref.invalidate(gatheringMembersProvider(id));
      state = const AsyncData(null);
      return true;
    } catch (error, stackTrace) {
      if (_isCurrentSession(token)) state = AsyncError(error, stackTrace);
      return false;
    }
  }

  /// 每个动作委托对应的数据操作，资源权限和事务继续由服务端处理。
  Future<void> _perform(GatheringLifecycleAction action) {
    final repository = ref.read(gatheringRepositoryProvider);
    return switch (action) {
      GatheringLifecycleAction.leave => repository.leave(id),
      GatheringLifecycleAction.end => repository.end(id),
      GatheringLifecycleAction.dissolve => repository.dissolve(id),
    };
  }

  bool _isCurrentSession(String? token) =>
      ref.mounted && ref.read(sessionProvider).asData?.value == token;
}
