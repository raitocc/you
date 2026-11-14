import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/auth/session.dart';
import '../../../../shared/models/cursor_page.dart';
import '../../../../shared/ui/paged_browse.dart';
import '../../../moment/data/moment_repository.dart';
import '../../../moment/models/moment.dart';
import '../../data/gathering_repository.dart';
import '../../models/gathering_moment.dart';

final gatheringMomentChoicesProvider = AsyncNotifierProvider.autoDispose
    .family<
      GatheringMomentChoicesViewModel,
      BrowseState<GatheringMomentSummary, MomentFeedCursor>,
      GatheringMomentOperation
    >(GatheringMomentChoicesViewModel.new);

/// 添加从本人瞬间中选；移出从当前同行中选，作者身份由页面明确展示。
class GatheringMomentChoicesViewModel
    extends PagedBrowse<GatheringMomentSummary, MomentFeedCursor> {
  GatheringMomentChoicesViewModel(this.operation);
  final GatheringMomentOperation operation;

  @override
  Future<GatheringMomentPageData> fetch(MomentFeedCursor? cursor) async {
    if (operation.removing) {
      return ref
          .read(gatheringRepositoryProvider)
          .moments(operation.gatheringId, cursor: cursor);
    }
    final page = await ref
        .read(momentRepositoryProvider)
        .browseYours(cursor: cursor);
    return (
      items: List<GatheringMomentSummary>.unmodifiable(
        page.items.map((item) => (moment: item, author: null)),
      ),
      nextCursor: page.nextCursor,
      hasMore: page.hasMore,
    );
  }

  @override
  String key(GatheringMomentSummary item) => item.moment.id;
}

typedef GatheringMomentSelectionState = ({
  Set<String> selected,
  bool busy,
  Object? error,
});
final gatheringMomentSelectionProvider = NotifierProvider.autoDispose
    .family<
      GatheringMomentSelectionViewModel,
      GatheringMomentSelectionState,
      GatheringMomentOperation
    >(GatheringMomentSelectionViewModel.new);

/// 选择独立于分页保存，整批失败后保留所选内容供调整与重试。
class GatheringMomentSelectionViewModel
    extends Notifier<GatheringMomentSelectionState> {
  GatheringMomentSelectionViewModel(this.operation);
  final GatheringMomentOperation operation;

  @override
  GatheringMomentSelectionState build() {
    ref.watch(sessionProvider);
    return (selected: const {}, busy: false, error: null);
  }

  void toggle(String id) {
    if (state.busy) return;
    final selected = {...state.selected};
    if (!selected.add(id)) selected.remove(id);
    state = (selected: Set.unmodifiable(selected), busy: false, error: null);
  }

  /// 服务端校验归属和同行冲突；成功广播内容变化，失败保留选择。
  Future<bool> submit() async {
    final current = state;
    if (current.busy || current.selected.isEmpty) return false;
    final token = ref.read(sessionProvider).asData?.value;
    state = (selected: current.selected, busy: true, error: null);
    try {
      await ref
          .read(gatheringRepositoryProvider)
          .changeMoments(
            operation.gatheringId,
            current.selected.toList(),
            removing: operation.removing,
          );
      if (!_isCurrentSession(token)) return false;
      ref.read(momentRevisionProvider.notifier).changed();
      state = (selected: const {}, busy: false, error: null);
      return true;
    } catch (error) {
      if (_isCurrentSession(token)) {
        state = (selected: current.selected, busy: false, error: error);
      }
      return false;
    }
  }

  bool _isCurrentSession(String? token) =>
      ref.mounted && ref.read(sessionProvider).asData?.value == token;
}
