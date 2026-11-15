import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/auth/session.dart';
import '../../data/related_repository.dart';
import '../states/related_feed_state.dart';

final relatedFeedProvider =
    AsyncNotifierProvider.autoDispose<RelatedFeedViewModel, RelatedFeedState>(
      RelatedFeedViewModel.new,
    );

/// 编排首屏、刷新与追加；会话或刷新变化后，旧请求的结果停止写入列表。
class RelatedFeedViewModel extends AsyncNotifier<RelatedFeedState> {
  int _generation = 0;

  @override
  Future<RelatedFeedState> build() async {
    // 会话变化触发重建，旧账号数据随之清空。
    _generation++;
    final token = ref.watch(sessionProvider).asData?.value;
    if (token == null) return const RelatedFeedState();
    return RelatedFeedState.fromPage(
      await ref.watch(relatedRepositoryProvider).browse(),
    );
  }

  /// 首页重试重新加载；已有列表刷新期间和失败后继续保留。
  Future<void> refresh() async {
    final old = state.asData?.value;
    if (old == null) {
      ref.invalidateSelf();
      return;
    }
    if (old.refreshing) return;
    final generation = ++_generation;
    state = AsyncData(
      RelatedFeedState(
        items: old.items,
        cursor: old.cursor,
        hasMore: old.hasMore,
        refreshing: true,
      ),
    );
    try {
      final page = await ref.read(relatedRepositoryProvider).browse();
      if (_isCurrent(generation)) {
        state = AsyncData(RelatedFeedState.fromPage(page));
      }
    } catch (error) {
      if (_isCurrent(generation)) {
        state = AsyncData(
          RelatedFeedState(
            items: old.items,
            cursor: old.cursor,
            hasMore: old.hasMore,
            refreshError: error,
          ),
        );
      }
    }
  }

  /// 按信箱动态 ID 去重追加，同一原内容的不同分享保持独立。
  Future<void> loadMore() async {
    final old = state.asData?.value;
    if (old == null || old.refreshing || old.loadingMore || !old.hasMore) {
      return;
    }
    final generation = _generation;
    state = AsyncData(
      RelatedFeedState(
        items: old.items,
        cursor: old.cursor,
        hasMore: old.hasMore,
        loadingMore: true,
      ),
    );
    try {
      final page = await ref
          .read(relatedRepositoryProvider)
          .browse(cursor: old.cursor);
      if (!_isCurrent(generation)) return;
      final seen = old.items.map((entry) => entry.id).toSet();
      state = AsyncData(
        RelatedFeedState(
          items: List.unmodifiable([
            ...old.items,
            ...page.items.where((entry) => seen.add(entry.id)),
          ]),
          cursor: page.nextCursor,
          hasMore:
              page.hasMore &&
              page.nextCursor != null &&
              page.nextCursor != old.cursor,
        ),
      );
    } catch (error) {
      if (_isCurrent(generation)) {
        state = AsyncData(
          RelatedFeedState(
            items: old.items,
            cursor: old.cursor,
            hasMore: old.hasMore,
            moreError: error,
          ),
        );
      }
    }
  }

  /// 只将本次会话及刷新轮次的结果交给界面。
  bool _isCurrent(int generation) => ref.mounted && generation == _generation;
}
