import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../shared/models/cursor_page.dart';
import '../../../../shared/ui/paged_browse.dart';
import '../../../../shared/models/content_revision.dart';
import '../../data/album_repository.dart';
import '../../models/album.dart';
import 'album_viewmodels.dart';

final albumListProvider =
    AsyncNotifierProvider.autoDispose<
      AlbumListViewModel,
      BrowseState<Album, AlbumListCursor>
    >(AlbumListViewModel.new);

class AlbumListViewModel extends PagedBrowse<Album, AlbumListCursor> {
  @override
  Future<BrowseState<Album, AlbumListCursor>> build() {
    ref.watch(momentRevisionProvider);
    ref.watch(recentAlbumsProvider);
    return super.build();
  }

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
  Future<BrowseState<AlbumTimelineItem, AlbumTimelineCursor>> build() {
    ref.watch(momentRevisionProvider);
    ref.watch(recentAlbumsProvider);
    return super.build();
  }

  @override
  Future<AlbumTimelinePage> fetch(AlbumTimelineCursor? cursor) =>
      ref.read(albumRepositoryProvider).browseMoments(id, cursor: cursor);
  @override
  String key(AlbumTimelineItem item) => item.id;
}
