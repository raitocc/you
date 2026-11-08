import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/auth/session.dart';
import '../../data/moment_repository.dart';
import '../../models/moment.dart';
import '../states/moment_feed_state.dart';

final momentFeedProvider = AsyncNotifierProvider.autoDispose
    .family<MomentFeedViewModel, MomentFeedState, MomentTab>(
      MomentFeedViewModel.new,
    );

class MomentFeedViewModel extends AsyncNotifier<MomentFeedState> {
  MomentFeedViewModel(this.tab);

  final MomentTab tab;
  int _generation = 0;

  /// 读取当前分类的首页数据。
  @override
  Future<MomentFeedState> build() async {
    _generation++;
    final token = ref.watch(sessionProvider).asData?.value;
    ref.watch(momentRevisionProvider);
    if (token == null || tab != MomentTab.yours) {
      return momentFeedState((items: [], nextCursor: null, hasMore: false));
    }
    return momentFeedState(
      await ref.watch(momentRepositoryProvider).browseYours(),
    );
  }

  /// 使用游标加载下一页并追加到列表。
  Future<void> loadMore() async {
    final current = state.asData?.value;
    if (state.isLoading ||
        current == null ||
        !current.hasMore ||
        current.loadingMore) {
      return;
    }

    final generation = _generation;
    final session = ref.read(sessionProvider).asData?.value;
    bool isCurrent() =>
        ref.mounted &&
        generation == _generation &&
        ref.read(sessionProvider).asData?.value == session;

    state = AsyncData((
      items: current.items,
      cursor: current.cursor,
      hasMore: true,
      loadingMore: true,
      moreError: null,
    ));
    try {
      final page = await ref
          .read(momentRepositoryProvider)
          .browseYours(cursor: current.cursor);
      if (!isCurrent()) return;
      final seen = current.items.map((moment) => moment.id).toSet();
      state = AsyncData(
        momentFeedState((
          items: List.unmodifiable([
            ...current.items,
            ...page.items.where((item) => seen.add(item.id)),
          ]),
          nextCursor: page.nextCursor,
          hasMore: page.hasMore && page.nextCursor != current.cursor,
        )),
      );
    } catch (error) {
      if (!isCurrent()) return;
      state = AsyncData((
        items: current.items,
        cursor: current.cursor,
        hasMore: true,
        loadingMore: false,
        moreError: error,
      ));
    }
  }
}
