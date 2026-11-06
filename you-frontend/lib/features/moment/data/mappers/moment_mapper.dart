import '../../models/moment.dart';
import '../../models/moment_mood.dart';

/// 将接口详情转换为前端瞬间模型。
Moment momentFromJson(
  Map<String, dynamic> json, {
  MomentAuthor? currentAuthor,
}) {
  final author = json['author'] as Map<String, dynamic>?;
  final location = json['location'] as Map<String, dynamic>?;
  return Moment(
    id: json['id'] as String,
    author:
        currentAuthor ??
        (
          id: author!['id'] as String,
          nickname: author['nickname'] as String?,
          avatarUrl: author['avatarUrl'] as String?,
        ),
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
    isOwn: currentAuthor != null || json['isOwn'] as bool,
    canEdit: currentAuthor != null || json['canEdit'] as bool,
    canDelete: json['canDelete'] as bool? ?? false,
    version: json['updatedAt'] as String,
  );
}

/// 解析瞬间卡片摘要及下一页游标。
MomentPageData momentPageFromJson(Map<String, dynamic> json) => (
  items: List.unmodifiable(
    _objects(json['items']).map(
      (item) => (
        id: item['id'] as String,
        title: item['title'] as String?,
        bodyPreview: item['bodyPreview'] as String?,
        coverUrl: item['coverUrl'] as String?,
        imageCount: item['imageCount'] as int,
        mood: momentMoodLabel(item['mood'] as String?),
        memoryTime: DateTime.parse(item['memoryTime'] as String),
      ),
    ),
  ),
  nextCursor: json['nextCursor'] as String?,
  hasMore: json['hasMore'] as bool,
);

Iterable<Map<String, dynamic>> _objects(Object? value) =>
    (value as List? ?? const []).cast<Map<String, dynamic>>();
