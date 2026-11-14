import '../../models/moment.dart';
import '../../models/moment_mood.dart';

/// 本人接口传入 currentAuthor 开启本人操作；共享详情沿用响应作者并保持只读。
Moment momentFromJson(
  Map<String, dynamic> json, {
  MomentAuthor? currentAuthor,
}) {
  final author = momentAuthorFromJson(json['author'] as Map<String, dynamic>?);
  final location = json['location'] as Map<String, dynamic>?;
  return Moment(
    id: json['id'] as String,
    author: currentAuthor ?? author,
    title: json['title'] as String?,
    body: json['body'] as String?,
    images: List.unmodifiable(
      _objects(json['images']).map(
        (image) => (id: image['fileId'] as String, url: image['url'] as String),
      ),
    ),
    tags: List.unmodifiable((json['tags'] as List).cast<String>()),
    mood: momentMoodLabel(json['mood'] as String?),
    memoryTime: DateTime.parse(json['memoryTime'] as String),
    location: location == null
        ? null
        : (
            name: location['name'] as String?,
            address: location['address'] as String?,
            latitude: location['latitude'] as num?,
            longitude: location['longitude'] as num?,
          ),
    sources: List.unmodifiable(
      _objects(json['sources']).map(
        (source) => (
          id: source['id'] as String,
          type: source['type'] as String,
          name: source['name'] as String,
        ),
      ),
    ),
    collections: List.unmodifiable(
      _objects(json['collections']).map(
        (collection) => (
          id: collection['id'] as String,
          name: collection['name'] as String,
        ),
      ),
    ),
    isOwn: currentAuthor != null,
    canEdit: currentAuthor != null,
    canDelete: currentAuthor != null,
    version: json['updatedAt'] as String,
  );
}

/// 解析瞬间卡片摘要及下一页游标。
MomentPageData momentPageFromJson(Map<String, dynamic> json) => (
  items: List.unmodifiable(_objects(json['items']).map(momentFeedItemFromJson)),
  nextCursor: switch (json['nextCursor']) {
    null => null,
    final Map<String, dynamic> cursor => (
      memoryTime: cursor['memoryTime'] as String,
      id: cursor['id'] as String,
    ),
    _ => throw const FormatException('Invalid moment feed cursor'),
  },
  hasMore: json['hasMore'] as bool,
);

Iterable<Map<String, dynamic>> _objects(Object? value) =>
    (value as List? ?? const []).cast<Map<String, dynamic>>();

/// 本人列表和同行列表共用同一摘要字段转换。
MomentFeedItem momentFeedItemFromJson(Map<String, dynamic> item) => (
  id: item['id'] as String,
  title: item['title'] as String?,
  bodyPreview: item['bodyPreview'] as String?,
  coverUrl: item['coverUrl'] as String?,
  imageCount: item['imageCount'] as int,
  mood: momentMoodLabel(item['mood'] as String?),
  memoryTime: DateTime.parse(item['memoryTime'] as String),
);

/// 公开资料缺失时保留空值，展示层保留对应记录。
MomentAuthor? momentAuthorFromJson(Map<String, dynamic>? json) {
  if (json == null) return null;
  return (
    id: json['id'] as String,
    nickname: json['nickname'] as String?,
    avatarUrl: json['avatarUrl'] as String?,
  );
}
