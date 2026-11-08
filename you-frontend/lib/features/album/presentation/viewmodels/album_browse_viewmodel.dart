import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/auth/session.dart';
import '../../../moment/data/moment_repository.dart';
import '../../data/album_repository.dart';
import '../../models/album.dart';
import 'album_viewmodels.dart';

typedef BrowseState<T, C> = ({
  List<T> items,
  C? cursor,
  bool hasMore,
  bool loadingMore,
  Object? error,
});
typedef BrowsePage<T, C> = ({List<T> items, C? nextCursor, bool hasMore});

abstract class PagedBrowse<T, C> extends AsyncNotifier<BrowseState<T, C>> {
  int _generation = 0;
  Future<BrowsePage<T, C>> fetch(C? cursor);
  String key(T item);
  @override
  Future<BrowseState<T, C>> build() async {
    _generation++;
    final token = ref.watch(sessionProvider).asData?.value;
    ref.watch(momentRevisionProvider);
    ref.watch(recentAlbumsProvider);
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

final albumListProvider =
    AsyncNotifierProvider.autoDispose<
      AlbumListViewModel,
      BrowseState<Album, AlbumListCursor>
    >(AlbumListViewModel.new);

class AlbumListViewModel extends PagedBrowse<Album, AlbumListCursor> {
  @override
  Future<AlbumPageData> fetch(AlbumListCursor? cursor) =>
      ref.read(albumRepositoryProvider).browse(cursor: cursor);
  @override
  String key(Album item) => item.id;
}

final albumTimelineProvider = AsyncNotifierProvider.autoDispose
    .family<
      AlbumTimelineViewModel,
      BrowseState<AlbumTimelineItem, AlbumTimelineCursor>,
      String
    >(AlbumTimelineViewModel.new);

class AlbumTimelineViewModel
    extends PagedBrowse<AlbumTimelineItem, AlbumTimelineCursor> {
  AlbumTimelineViewModel(this.id);
  final String id;
  @override
  Future<AlbumTimelinePage> fetch(AlbumTimelineCursor? cursor) =>
      ref.read(albumRepositoryProvider).browseMoments(id, cursor: cursor);
  @override
  String key(AlbumTimelineItem item) => item.id;
}
