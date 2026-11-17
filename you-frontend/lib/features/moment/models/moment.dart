enum MomentTab {
  yours('你', '自己发布的所有瞬间'),
  related('与你有关', '相关同行与圈子中的瞬间');

  const MomentTab(this.label, this.description);
  final String label;
  final String description;
}

typedef MomentAuthor = ({String id, String? nickname, String? avatarUrl});
typedef MomentImage = ({String id, String url});
typedef MomentSource = ({String id, String type, String name});
typedef MomentCollection = ({String id, String name});
typedef MomentLocation = ({
  String? name,
  String? address,
  num? latitude,
  num? longitude,
});

class Moment {
  const Moment({
    required this.id,
    required this.author,
    required this.title,
    required this.body,
    required this.images,
    required this.tags,
    required this.mood,
    required this.memoryTime,
    required this.location,
    required this.sources,
    required this.collections,
    required this.isOwn,
    required this.canEdit,
    required this.canDelete,
    required this.version,
  });

  final String id;

  /// 作者公开资料，资料缺失时保留 null 并继续展示记录。
  final MomentAuthor? author;
  final String? title;
  final String? body;
  final List<MomentImage> images;
  final List<String> tags;
  final String? mood;
  final DateTime memoryTime;
  final MomentLocation? location;
  final List<MomentSource> sources;
  final List<MomentCollection> collections;
  final bool isOwn;
  final bool canEdit;
  final bool canDelete;

  /// 服务端原始 updatedAt 字符串，用于记录草稿对应的版本。
  final String version;
}

typedef MomentFeedItem = ({
  String id,
  String? title,
  String? bodyPreview,
  String? coverUrl,
  int imageCount,
  String? mood,
  DateTime memoryTime,
});

/// 服务端分页边界，时间原样回传，避免重新格式化。
typedef MomentFeedCursor = ({String memoryTime, String id});

typedef MomentPageData = ({
  List<MomentFeedItem> items,
  MomentFeedCursor? nextCursor,
  bool hasMore,
});
