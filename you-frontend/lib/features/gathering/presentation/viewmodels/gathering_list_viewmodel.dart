import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../shared/models/cursor_page.dart';
import '../../../../shared/models/content_revision.dart';
import '../../../../shared/ui/paged_browse.dart';
import '../../data/gathering_repository.dart';
import '../../models/gathering.dart';

final gatheringListProvider =
    AsyncNotifierProvider.autoDispose<
      GatheringListViewModel,
      BrowseState<GatheringSummary, GatheringCursor>
    >(GatheringListViewModel.new);

/// 沿用公共分页机制，保留加载失败前的列表与游标。
class GatheringListViewModel
    extends PagedBrowse<GatheringSummary, GatheringCursor> {
  @override
  Future<BrowseState<GatheringSummary, GatheringCursor>> build() {
    ref.watch(momentRevisionProvider);
    return super.build();
  }

  @override
  Future<GatheringPageData> fetch(GatheringCursor? cursor) =>
      ref.read(gatheringRepositoryProvider).browse(cursor: cursor);

  @override
  String key(GatheringSummary item) => item.id;

  /// 重建首屏；错误已保存在 provider 状态中，页面展示重试入口。
  Future<void> refresh() async {
    ref.invalidateSelf();
    try {
      await future;
    } catch (_) {
      // AsyncError 由列表页面展示；结束下拉刷新动画并保留错误状态。
    }
  }
}
