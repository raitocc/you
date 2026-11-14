import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/auth/session.dart';
import '../../../../shared/models/content_revision.dart';
import '../../data/gathering_repository.dart';

final momentGatheringAssociationProvider = AsyncNotifierProvider.autoDispose
    .family<MomentGatheringAssociationViewModel, void, String>(
      MomentGatheringAssociationViewModel.new,
    );

/// 本人详情中的快捷关联入口，复用同行批量接口提交一个 ID。
class MomentGatheringAssociationViewModel extends AsyncNotifier<void> {
  MomentGatheringAssociationViewModel(this.momentId);
  final String momentId;

  @override
  void build() {
    ref.watch(sessionProvider);
  }

  Future<bool> associate(String gatheringId) async {
    if (state.isLoading) return false;
    final token = ref.read(sessionProvider).asData?.value;
    state = const AsyncLoading();
    try {
      await ref.read(gatheringRepositoryProvider).changeMoments(gatheringId, [
        momentId,
      ], removing: false);
      if (!_isCurrentSession(token)) return false;
      ref.read(momentRevisionProvider.notifier).changed();
      state = const AsyncData(null);
      return true;
    } catch (error, stackTrace) {
      if (_isCurrentSession(token)) state = AsyncError(error, stackTrace);
      return false;
    }
  }

  bool _isCurrentSession(String? token) =>
      ref.mounted && ref.read(sessionProvider).asData?.value == token;
}
