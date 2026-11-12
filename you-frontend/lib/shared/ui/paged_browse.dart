import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/session.dart';
import '../models/cursor_page.dart';

abstract class PagedBrowse<T, C> extends AsyncNotifier<BrowseState<T, C>> {
  int _generation = 0;
  Future<BrowsePage<T, C>> fetch(C? cursor);
  String key(T item);
  @override
  Future<BrowseState<T, C>> build() async {
    _generation++;
    final token = ref.watch(sessionProvider).asData?.value;
    if (token == null) {
      return (
        items: <T>[],
        cursor: null,
        hasMore: false,
        loadingMore: false,
        error: null,
      );
    }
    final page = await fetch(null);
    return (
      items: page.items,
      cursor: page.nextCursor,
      hasMore: page.hasMore && page.nextCursor != null,
      loadingMore: false,
      error: null,
    );
  }

  Future<void> loadMore() async {
    final old = state.asData?.value;
    if (state.isLoading || old == null || !old.hasMore || old.loadingMore) {
      return;
    }
    final generation = _generation;
    state = AsyncData((
      items: old.items,
      cursor: old.cursor,
      hasMore: old.hasMore,
      loadingMore: true,
      error: null,
    ));
    try {
      final page = await fetch(old.cursor);
      if (!ref.mounted || generation != _generation) return;
      final seen = old.items.map(key).toSet();
      state = AsyncData((
        items: List.unmodifiable([
          ...old.items,
          ...page.items.where((item) => seen.add(key(item))),
        ]),
        cursor: page.nextCursor,
        hasMore:
            page.hasMore &&
            page.nextCursor != null &&
            page.nextCursor != old.cursor,
        loadingMore: false,
        error: null,
      ));
    } catch (e) {
      if (ref.mounted && generation == _generation) {
        state = AsyncData((
          items: old.items,
          cursor: old.cursor,
          hasMore: old.hasMore,
          loadingMore: false,
          error: e,
        ));
      }
    }
  }
}
