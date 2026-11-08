class Album {
  const Album({
    required this.id,
    required this.name,
    required this.description,
    required this.coverFileId,
    required this.coverUrl,
    required this.organizeMode,
    required this.momentCount,
    required this.createdAt,
    required this.updatedAt,
  });
  final String id, name, organizeMode;
  final String? description, coverFileId, coverUrl;
  final int momentCount;
  final DateTime createdAt, updatedAt;
}

typedef AlbumMoment = ({
  String id,
  String? title,
  String? preview,
  String? coverUrl,
});
typedef AlbumCursor = ({String memoryTime, String id});
typedef AlbumMomentPage = ({
  List<AlbumMoment> items,
  AlbumCursor? cursor,
  bool hasMore,
});

// 时间字符串原样保存，避免分页边界丢失精度。
typedef AlbumListCursor = ({String createdAt, String id});
typedef AlbumTimelineCursor = ({
  String memoryTime,
  String createdAt,
  String id,
});
typedef AlbumPageData = ({
  List<Album> items,
  AlbumListCursor? nextCursor,
  bool hasMore,
});
typedef AlbumTimelinePage = ({
  List<AlbumTimelineItem> items,
  AlbumTimelineCursor? nextCursor,
  bool hasMore,
});

typedef AlbumTimelineItem = ({
  String id,
  String? title,
  String? body,
  List<String> images,
  DateTime memoryTime,
  String? place,
  String? mood,
  List<String> tags,
});
