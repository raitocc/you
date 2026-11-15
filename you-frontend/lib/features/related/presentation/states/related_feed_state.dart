import '../../../../shared/models/cursor_page.dart';
import '../../models/related_entry.dart';

/// 列表更新失败保留已有内容，刷新与追加加载各自记录操作状态。
class RelatedFeedState {
  const RelatedFeedState({
    this.items = const [],
    this.cursor,
    this.hasMore = false,
    this.refreshing = false,
    this.loadingMore = false,
    this.moreError,
    this.refreshError,
  });
  factory RelatedFeedState.fromPage(
    BrowsePage<RelatedEntry, RelatedCursor> page,
  ) => RelatedFeedState(
    items: page.items,
    cursor: page.nextCursor,
    hasMore: page.hasMore && page.nextCursor != null,
  );
  final List<RelatedEntry> items;
  final RelatedCursor? cursor;
  final bool hasMore;
  final bool refreshing;
  final bool loadingMore;
  final Object? moreError;
  final Object? refreshError;
}
