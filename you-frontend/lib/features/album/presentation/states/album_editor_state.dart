import 'dart:typed_data';
import '../../models/album.dart';

class AlbumEditorState {
  const AlbumEditorState({
    this.busy = false,
    this.error,
    this.coverId,
    this.cover,
    this.items = const [],
    this.selected = const {},
    this.cursor,
    this.hasMore = true,
    this.loading = false,
    this.momentsError,
  });
  final bool busy, loading, hasMore;
  final Object? error, momentsError;
  final String? coverId;
  final Uint8List? cover;
  final List<AlbumMoment> items;
  final Set<String> selected;
  final AlbumCursor? cursor;
  AlbumEditorState copy({
    bool? busy,
    bool? loading,
    bool? hasMore,
    Object? error,
    Object? momentsError,
    String? coverId,
    Uint8List? cover,
    List<AlbumMoment>? items,
    Set<String>? selected,
    AlbumCursor? cursor,
    bool clearCover = false,
  }) => AlbumEditorState(
    busy: busy ?? this.busy,
    loading: loading ?? this.loading,
    hasMore: hasMore ?? this.hasMore,
    error: error,
    momentsError: momentsError,
    coverId: clearCover ? null : coverId ?? this.coverId,
    cover: clearCover ? null : cover ?? this.cover,
    items: items ?? this.items,
    selected: selected ?? this.selected,
    cursor: cursor ?? this.cursor,
  );
}
