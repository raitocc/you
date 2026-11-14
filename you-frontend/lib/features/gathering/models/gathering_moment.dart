import '../../../shared/models/cursor_page.dart';
import '../../moment/models/moment.dart';

/// 同行时间线复用瞬间摘要，额外保留可空的作者公开资料。
typedef GatheringMomentSummary = ({
  MomentFeedItem moment,
  MomentAuthor? author,
});
typedef GatheringMomentPageData =
    BrowsePage<GatheringMomentSummary, MomentFeedCursor>;

/// 批量操作的目标及模式，分别保存选择和提交状态。
typedef GatheringMomentOperation = ({String gatheringId, bool removing});
