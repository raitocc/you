import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/auth/session.dart';
import '../../../../shared/models/cursor_page.dart';
import '../../../../shared/models/content_revision.dart';
import '../../../../shared/ui/paged_browse.dart';
import '../../../moment/models/moment.dart';
import '../../data/gathering_repository.dart';
import '../../models/gathering_moment.dart';

final gatheringMomentsProvider = AsyncNotifierProvider.autoDispose
    .family<
      GatheringMomentsViewModel,
      BrowseState<GatheringMomentSummary, MomentFeedCursor>,
      String
    >(GatheringMomentsViewModel.new);

/// 同行时间线使用公共游标分页，瞬间变化后刷新当前会话的数据。
class GatheringMomentsViewModel
    extends PagedBrowse<GatheringMomentSummary, MomentFeedCursor> {
  GatheringMomentsViewModel(this.id);
  final String id;

  @override
  Future<BrowseState<GatheringMomentSummary, MomentFeedCursor>> build() {
    ref.watch(momentRevisionProvider);
    return super.build();
  }

  @override
  Future<GatheringMomentPageData> fetch(MomentFeedCursor? cursor) =>
      ref.read(gatheringRepositoryProvider).moments(id, cursor: cursor);

  @override
  String key(GatheringMomentSummary item) => item.moment.id;

  /// 错误由页面展示，刷新等待结束后收起下拉动画。
  Future<void> refresh() async {
    ref.invalidateSelf();
    try {
      await future;
    } catch (_) {
      // 页面通过 AsyncError 展示重试入口。
    }
  }
}

typedef GatheringMomentKey = ({String gatheringId, String momentId});
final gatheringMomentDetailProvider = FutureProvider.autoDispose
    .family<Moment, GatheringMomentKey>((ref, key) {
      ref.watch(sessionProvider);
      ref.watch(momentRevisionProvider);
      return ref
          .watch(gatheringRepositoryProvider)
          .moment(key.gatheringId, key.momentId);
    }, retry: (_, _) => null);
