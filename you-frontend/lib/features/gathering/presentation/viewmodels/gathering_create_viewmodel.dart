import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/auth/session.dart';
import '../../data/gathering_repository.dart';
import '../../models/gathering.dart';
import 'gathering_list_viewmodel.dart';

final gatheringCreateProvider =
    AsyncNotifierProvider.autoDispose<GatheringCreateViewModel, void>(
      GatheringCreateViewModel.new,
    );

/// 管理创建请求的提交状态，页面表单负责保存输入。
class GatheringCreateViewModel extends AsyncNotifier<void> {
  @override
  void build() {
    ref.watch(sessionProvider);
  }

  /// 整理名称和空介绍，成功后刷新本人列表；失败交给页面展示。
  Future<Gathering?> create(String name, String description) async {
    if (state.isLoading) return null;
    final token = ref.read(sessionProvider).asData?.value;
    state = const AsyncLoading();

    try {
      final gathering = await ref
          .read(gatheringRepositoryProvider)
          .create(
            name: name.trim(),
            description: description.trim().isEmpty ? null : description,
          );
      if (!_isCurrentSession(token)) return null;
      ref.invalidate(gatheringListProvider);
      state = const AsyncData(null);
      return gathering;
    } catch (error, stackTrace) {
      if (_isCurrentSession(token)) {
        state = AsyncError(error, stackTrace);
      }
      return null;
    }
  }

  /// 页面离开或切换账号后，结束旧请求的界面处理。
  bool _isCurrentSession(String? token) =>
      ref.mounted && ref.read(sessionProvider).asData?.value == token;
}
