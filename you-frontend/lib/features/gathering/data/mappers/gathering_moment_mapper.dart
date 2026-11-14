import '../../../moment/data/mappers/moment_mapper.dart';
import '../../models/gathering_moment.dart';

/// 保持服务端时间线顺序及原始游标时间，作者缺失的记录仍进入列表。
GatheringMomentPageData gatheringMomentPageFromJson(Map<String, dynamic> json) {
  final cursor = json['nextCursor'] as Map<String, dynamic>?;
  return (
    items: List<GatheringMomentSummary>.unmodifiable(
      (json['items'] as List).cast<Map<String, dynamic>>().map(
        (item) => (
          moment: momentFeedItemFromJson(item),
          author: momentAuthorFromJson(item['author'] as Map<String, dynamic>?),
        ),
      ),
    ),
    nextCursor: cursor == null
        ? null
        : (
            memoryTime: cursor['memoryTime'] as String,
            id: cursor['id'] as String,
          ),
    hasMore: json['hasMore'] as bool,
  );
}
