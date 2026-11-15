import '../../../../shared/models/cursor_page.dart';
import '../../../moment/data/mappers/moment_mapper.dart';
import '../../models/related_entry.dart';

/// 解析混合摘要和原样时间游标，固定字段直接遵循接口契约。
BrowsePage<RelatedEntry, RelatedCursor> relatedPageFromJson(
  Map<String, dynamic> json,
) => (
  items: List.unmodifiable(
    (json['items'] as List).cast<Map<String, dynamic>>().map(
      relatedEntryFromJson,
    ),
  ),
  nextCursor: switch (json['nextCursor']) {
    null => null,
    final Map<String, dynamic> cursor => (
      sharedAt: cursor['sharedAt'] as String,
      id: cursor['id'] as String,
    ),
    _ => throw const FormatException('Invalid related cursor'),
  },
  hasMore: json['hasMore'] as bool,
);

/// 根据内容类型构造明确模型，分享标识与原内容标识分别保存。
RelatedEntry relatedEntryFromJson(Map<String, dynamic> json) {
  final source = json['source'] as Map<String, dynamic>;
  final origin = (
    type: RelatedSourceType.values.byName(source['type'] as String),
    id: source['id'] as String,
    name: source['name'] as String,
  );
  final actor = momentAuthorFromJson(json['actor'] as Map<String, dynamic>?);
  final id = json['id'] as String;
  final time = DateTime.parse(json['sharedAt'] as String);
  final shareId = json['shareId'] as String?;
  switch (json['objectType']) {
    case 'moment':
      final moment = json['moment'] as Map<String, dynamic>;
      return RelatedMomentEntry(
        id: id,
        sharedAt: time,
        source: origin,
        shareId: shareId,
        actor: actor,
        moment: momentFeedItemFromJson(moment),
        author: momentAuthorFromJson(moment['author'] as Map<String, dynamic>?),
      );
    case 'album':
      final album = json['album'] as Map<String, dynamic>;
      return RelatedAlbumEntry(
        id: id,
        sharedAt: time,
        source: origin,
        shareId: shareId,
        actor: actor,
        album: (
          id: album['id'] as String,
          name: album['name'] as String,
          descriptionPreview: album['descriptionPreview'] as String?,
          coverUrl: album['coverUrl'] as String?,
          momentCount: album['momentCount'] as int,
          owner: momentAuthorFromJson(album['owner'] as Map<String, dynamic>?),
        ),
      );
    default:
      throw const FormatException('Invalid related object type');
  }
}
