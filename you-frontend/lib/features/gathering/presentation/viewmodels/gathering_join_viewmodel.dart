import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/auth/session.dart';
import '../../data/gathering_repository.dart';
import '../../models/gathering.dart';
import 'gathering_detail_viewmodel.dart';
import 'gathering_list_viewmodel.dart';
import 'gathering_members_viewmodel.dart';

/// code 与 preview 保持同一次查询的对应关系，确认时使用这份邀请码。
typedef GatheringJoinState = ({
  String code,
  GatheringJoinPreview? preview,
  bool busy,
  Object? error,
});

final gatheringJoinProvider =
    NotifierProvider.autoDispose<GatheringJoinViewModel, GatheringJoinState>(
      GatheringJoinViewModel.new,
    );

/// 手动输入与扫码共用预览、确认和错误状态。
class GatheringJoinViewModel extends Notifier<GatheringJoinState> {
  @override
  GatheringJoinState build() {
    ref.watch(sessionProvider);
    return (code: '', preview: null, busy: false, error: null);
  }

  /// 修改输入后清除旧目标，用户再次预览后才能确认加入。
  void changeCode(String code) {
    if (state.busy) return;
    state = (code: code, preview: null, busy: false, error: null);
  }

  /// 仅查询目标信息，成员关系在用户确认后建立。
  Future<void> preview(String code) async {
    if (state.busy) return;
    final token = ref.read(sessionProvider).asData?.value;
    final normalized = code.trim();
    state = (code: normalized, preview: null, busy: true, error: null);
    try {
      final result = await ref
          .read(gatheringRepositoryProvider)
          .previewJoin(normalized);
      if (!_isCurrentSession(token)) return;
      state = (code: normalized, preview: result, busy: false, error: null);
    } catch (error) {
      if (_isCurrentSession(token)) {
        state = (code: normalized, preview: null, busy: false, error: error);
      }
    }
  }

  /// 提交已预览的邀请码；成功后刷新关联页面的数据。
  Future<Gathering?> join() async {
    final current = state;
    final target = current.preview;
    if (current.busy || target == null || target.isMember) {
      return null;
    }
    final token = ref.read(sessionProvider).asData?.value;
    state = (
      code: current.code,
      preview: current.preview,
      busy: true,
      error: null,
    );
    try {
      final result = await ref
          .read(gatheringRepositoryProvider)
          .join(current.code);
      if (!_isCurrentSession(token)) return null;
      ref.invalidate(gatheringListProvider);
      ref.invalidate(gatheringDetailProvider(result.id));
      ref.invalidate(gatheringMembersProvider(result.id));
      state = (
        code: current.code,
        preview: current.preview,
        busy: false,
        error: null,
      );
      return result;
    } catch (error) {
      if (_isCurrentSession(token)) {
        state = (
          code: current.code,
          preview: current.preview,
          busy: false,
          error: error,
        );
      }
      return null;
    }
  }

  /// 页面离开或切换账号后，结束旧请求的界面处理。
  bool _isCurrentSession(String? token) =>
      ref.mounted && ref.read(sessionProvider).asData?.value == token;
}
