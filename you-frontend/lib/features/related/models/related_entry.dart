import '../../moment/models/moment.dart';

/// 来源用于打开共享空间，内容 ID 和分享关系用于后续共享详情导航。
enum RelatedSourceType { gathering, circle }

typedef RelatedSource = ({RelatedSourceType type, String id, String name});

/// 时间原样回传服务端，分页以动态 ID 区分独立分享。
typedef RelatedCursor = ({String sharedAt, String id});

/// 一次分享行为的一张动态卡片；同一内容的多个来源分别保留。
sealed class RelatedEntry {
  const RelatedEntry({
    required this.id,
    required this.sharedAt,
    required this.source,
    required this.shareId,
    required this.actor,
  });
  final String id;
  final DateTime sharedAt;
  final RelatedSource source;
  final String? shareId;
  final MomentAuthor? actor;
}

/// 瞬间摘要复用现有卡片模型，作者资料单独保留。
final class RelatedMomentEntry extends RelatedEntry {
  const RelatedMomentEntry({
    required super.id,
    required super.sharedAt,
    required super.source,
    required super.shareId,
    required super.actor,
    required this.moment,
    required this.author,
  });
  final MomentFeedItem moment;
  final MomentAuthor? author;
}

/// 时光只加载卡片所需摘要，内部瞬间由共享时光入口读取。
final class RelatedAlbumEntry extends RelatedEntry {
  const RelatedAlbumEntry({
    required super.id,
    required super.sharedAt,
    required super.source,
    required super.shareId,
    required super.actor,
    required this.album,
  });
  final RelatedAlbumSummary album;
}

typedef RelatedAlbumSummary = ({
  String id,
  String name,
  String? descriptionPreview,
  String? coverUrl,
  int momentCount,
  MomentAuthor? owner,
});
