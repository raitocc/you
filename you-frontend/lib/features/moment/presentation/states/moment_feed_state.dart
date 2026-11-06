import '../../models/moment.dart';

typedef MomentFeedState = ({
  List<Moment> items,
  String? cursor,
  bool hasMore,
  bool loadingMore,
  Object? moreError,
});

MomentFeedState momentFeedState(MomentPageData page) => (
  items: page.items,
  cursor: page.nextCursor,
  hasMore: page.hasMore && page.nextCursor != null,
  loadingMore: false,
  moreError: null,
);
