typedef BrowseState<T, C> = ({
  List<T> items,
  C? cursor,
  bool hasMore,
  bool loadingMore,
  Object? error,
});
typedef BrowsePage<T, C> = ({List<T> items, C? nextCursor, bool hasMore});
