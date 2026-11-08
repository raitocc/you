import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/auth/session.dart';
import '../../data/album_repository.dart';
import '../../models/album.dart';
import 'album_browse_viewmodel.dart';
import 'album_viewmodels.dart';

typedef AlbumMomentSelection = ({String id, bool removing});

class AlbumMomentsState {
  const AlbumMomentsState({
    this.items = const [],
    this.selected = const {},
    this.cursor,
    this.timelineCursor,
    this.hasMore = true,
    this.loading = false,
    this.busy = false,
    this.loadError,
    this.saveError,
  });
  final List<AlbumMoment> items;
  final Set<String> selected;
  final AlbumCursor? cursor;
  final AlbumTimelineCursor? timelineCursor;
  final bool hasMore, loading, busy;
  final Object? loadError, saveError;
  AlbumMomentsState copy({
    List<AlbumMoment>? items,
    Set<String>? selected,
    AlbumCursor? cursor,
    AlbumTimelineCursor? timelineCursor,
    bool? hasMore,
    bool? loading,
    bool? busy,
    Object? loadError,
    Object? saveError,
  }) => AlbumMomentsState(
    items: items ?? this.items,
    selected: selected ?? this.selected,
    cursor: cursor ?? this.cursor,
    timelineCursor: timelineCursor ?? this.timelineCursor,
    hasMore: hasMore ?? this.hasMore,
    loading: loading ?? this.loading,
    busy: busy ?? this.busy,
    loadError: loadError,
    saveError: saveError,
  );
}

final albumMomentsProvider = NotifierProvider.autoDispose
    .family<AlbumMomentsViewModel, AlbumMomentsState, AlbumMomentSelection>(
      AlbumMomentsViewModel.new,
    );

class AlbumMomentsViewModel extends Notifier<AlbumMomentsState> {
  AlbumMomentsViewModel(this.selection);
  final AlbumMomentSelection selection;
  int _generation = 0;
  @override
  AlbumMomentsState build() {
    ref.watch(sessionProvider);
    _generation++;
    return const AlbumMomentsState();
  }

  Future<void> loadMore() async {
    if (state.loading || state.busy || !state.hasMore) return;
    final before = state;
    final generation = _generation;
    final token = ref.read(sessionProvider).asData?.value;
    bool current() =>
        ref.mounted &&
        generation == _generation &&
        ref.read(sessionProvider).asData?.value == token;
    state = state.copy(loading: true);
    try {
      final repository = ref.read(albumRepositoryProvider);
      final List<AlbumMoment> items;
      final AlbumCursor? cursor;
      final AlbumTimelineCursor? timelineCursor;
      final bool hasMore;
      if (selection.removing) {
        final page = await repository.browseMoments(
          selection.id,
          cursor: before.timelineCursor,
        );
        items = page.items
            .map(
              (item) => (
                id: item.id,
                title: item.title,
                preview: item.body,
                coverUrl: item.images.firstOrNull,
              ),
            )
            .toList();
        cursor = null;
        timelineCursor = page.nextCursor;
        hasMore =
            page.hasMore &&
            timelineCursor != null &&
            timelineCursor != before.timelineCursor;
      } else {
        final page = await repository.moments(before.cursor);
        items = page.items;
        cursor = page.cursor;
        timelineCursor = null;
        hasMore = page.hasMore && cursor != null && cursor != before.cursor;
      }
      if (!current()) return;
      final seen = before.items.map((item) => item.id).toSet();
      state = state.copy(
        items: List.unmodifiable([
          ...before.items,
          ...items.where((item) => seen.add(item.id)),
        ]),
        cursor: cursor,
        timelineCursor: timelineCursor,
        hasMore: hasMore,
        loading: false,
      );
    } catch (error) {
      if (current()) state = state.copy(loading: false, loadError: error);
    }
  }

  void toggle(String id) {
    if (state.busy || !state.items.any((item) => item.id == id)) return;
    final selected = {...state.selected};
    if (!selected.add(id)) selected.remove(id);
    state = state.copy(
      selected: Set.unmodifiable(selected),
      loadError: state.loadError,
    );
  }

  Future<bool> submit() async {
    if (state.busy || state.loading || state.selected.isEmpty) return false;
    final generation = _generation;
    final token = ref.read(sessionProvider).asData?.value;
    bool current() =>
        ref.mounted &&
        generation == _generation &&
        ref.read(sessionProvider).asData?.value == token;
    final ids = state.selected.toList(growable: false);
    state = state.copy(busy: true, loadError: state.loadError);
    try {
      final repository = ref.read(albumRepositoryProvider);
      final album = selection.removing
          ? await repository.removeMoments(selection.id, ids)
          : await repository.addMoments(selection.id, ids);
      if (!current()) return false;
      ref.read(recentAlbumsProvider.notifier).remember(album);
      ref.invalidate(albumDetailProvider(selection.id));
      ref.invalidate(albumTimelineProvider(selection.id));
      state = state.copy(busy: false, selected: const {});
      return true;
    } catch (error) {
      if (current()) {
        state = state.copy(
          busy: false,
          saveError: error,
          loadError: state.loadError,
        );
      }
      return false;
    }
  }
}
