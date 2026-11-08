import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/auth/session.dart';
import '../../data/album_repository.dart';
import '../../../moment/data/moment_repository.dart';
import '../../models/album.dart';
import '../states/album_editor_state.dart';

// 当前会话内的时光变更记录，用于通知列表刷新。
final recentAlbumsProvider = NotifierProvider<RecentAlbums, List<Album>>(
  RecentAlbums.new,
);

class RecentAlbums extends Notifier<List<Album>> {
  @override
  List<Album> build() {
    ref.watch(sessionProvider);
    return [];
  }

  void remove(String id) =>
      state = state.where((album) => album.id != id).toList();

  void remember(Album album) =>
      state = [album, ...state.where((a) => a.id != album.id)];
}

final albumDetailProvider = FutureProvider.autoDispose.family<Album, String>((
  ref,
  id,
) async {
  final token = ref.watch(sessionProvider).asData?.value;
  ref.watch(momentRevisionProvider);
  final album = await ref.watch(albumRepositoryProvider).detail(id);
  if (ref.mounted && ref.read(sessionProvider).asData?.value == token) {
    ref.read(recentAlbumsProvider.notifier).remember(album);
  }
  return album;
});
final albumEditorProvider =
    NotifierProvider.autoDispose<AlbumEditorViewModel, AlbumEditorState>(
      AlbumEditorViewModel.new,
    );

final albumEditProvider = NotifierProvider.autoDispose
    .family<AlbumEditorViewModel, AlbumEditorState, Album>(
      (album) => AlbumEditorViewModel(album),
    );

class AlbumEditorViewModel extends Notifier<AlbumEditorState> {
  AlbumEditorViewModel([this.initial]);
  final Album? initial;
  @override
  AlbumEditorState build() {
    ref.watch(sessionProvider);
    return AlbumEditorState(coverId: initial?.coverFileId);
  }

  Future<void> loadMoments() async {
    if (state.loading || !state.hasMore) return;
    final token = ref.read(sessionProvider).asData?.value;
    final before = state;
    state = state.copy(loading: true);
    try {
      final page = await ref
          .read(albumRepositoryProvider)
          .moments(before.cursor);
      if (!ref.mounted || ref.read(sessionProvider).asData?.value != token) {
        return;
      }
      final seen = state.items.map((e) => e.id).toSet();
      state = state.copy(
        loading: false,
        items: [...state.items, ...page.items.where((e) => seen.add(e.id))],
        cursor: page.cursor,
        hasMore:
            page.hasMore && page.cursor != null && page.cursor != before.cursor,
      );
    } catch (e) {
      if (ref.mounted && ref.read(sessionProvider).asData?.value == token) {
        state = state.copy(loading: false, momentsError: e);
      }
    }
  }

  void toggle(String id) {
    if (state.busy) return;
    final selected = {...state.selected};
    if (!selected.add(id)) selected.remove(id);
    state = state.copy(selected: selected);
  }

  void clearCover() {
    if (!state.busy) state = state.copy(clearCover: true);
  }

  void reportError(Object error) => state = state.copy(error: error);
  Future<void> cover(Uint8List bytes, String mime) async {
    if (state.busy) return;
    final token = ref.read(sessionProvider).asData?.value;
    state = state.copy(busy: true);
    try {
      final id = await ref.read(albumRepositoryProvider).upload(bytes, mime);
      if (ref.mounted && ref.read(sessionProvider).asData?.value == token) {
        state = state.copy(busy: false, coverId: id, cover: bytes);
      }
    } catch (e) {
      if (ref.mounted && ref.read(sessionProvider).asData?.value == token) {
        state = state.copy(busy: false, error: e);
      }
    }
  }

  Future<Album?> create(String name, String description) async {
    if (state.busy) return null;
    final token = ref.read(sessionProvider).asData?.value;
    state = state.copy(busy: true);
    try {
      final repository = ref.read(albumRepositoryProvider);
      final album = initial != null
          ? await repository.update(
              initial!.id,
              name: name,
              description: description,
              coverFileId: state.coverId,
            )
          : await repository.create(
              name: name,
              description: description,
              coverFileId: state.coverId,
              momentIds: state.selected.toList(),
            );
      if (!ref.mounted || ref.read(sessionProvider).asData?.value != token) {
        return null;
      }
      ref.read(recentAlbumsProvider.notifier).remember(album);
      state = state.copy(busy: false);
      return album;
    } catch (e) {
      if (ref.mounted && ref.read(sessionProvider).asData?.value == token) {
        state = state.copy(busy: false, error: e);
      }
      return null;
    }
  }
}

final albumDeleteProvider = NotifierProvider.autoDispose
    .family<AlbumDeleteViewModel, ({bool busy, Object? error}), String>(
      AlbumDeleteViewModel.new,
    );

class AlbumDeleteViewModel extends Notifier<({bool busy, Object? error})> {
  AlbumDeleteViewModel(this.id);
  final String id;
  @override
  ({bool busy, Object? error}) build() {
    ref.watch(sessionProvider);
    return (busy: false, error: null);
  }

  Future<bool> delete() async {
    if (state.busy) return false;
    final token = ref.read(sessionProvider).asData?.value;
    state = (busy: true, error: null);
    try {
      await ref.read(albumRepositoryProvider).delete(id);
      if (!ref.mounted || ref.read(sessionProvider).asData?.value != token) {
        return false;
      }
      ref.read(recentAlbumsProvider.notifier).remove(id);
      state = (busy: false, error: null);
      return true;
    } catch (error) {
      if (ref.mounted && ref.read(sessionProvider).asData?.value == token) {
        state = (busy: false, error: error);
      }
      return false;
    }
  }
}
