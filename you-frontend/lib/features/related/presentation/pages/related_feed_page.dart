import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../app/theme/app_theme.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../models/related_entry.dart';
import '../states/related_feed_state.dart';
import '../viewmodels/related_feed_viewmodel.dart';
import '../widgets/related_card.dart';

/// 独立保留 Related 的分页数据与滚动位置。
class RelatedFeedPage extends ConsumerStatefulWidget {
  const RelatedFeedPage({super.key});

  @override
  ConsumerState<RelatedFeedPage> createState() => _RelatedFeedPageState();
}

class _RelatedFeedPageState extends ConsumerState<RelatedFeedPage>
    with AutomaticKeepAliveClientMixin {
  final _scroll = ScrollController();

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_nearEnd);
  }

  void _nearEnd() {
    if (!_scroll.hasClients || _scroll.position.extentAfter > 360) return;
    if (ref.read(relatedFeedProvider).asData?.value.moreError == null) {
      ref.read(relatedFeedProvider.notifier).loadMore();
    }
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final value = ref.watch(relatedFeedProvider);
    final feed = value.asData?.value;
    final notifier = ref.read(relatedFeedProvider.notifier);
    if (feed == null && value.isLoading) return const LoadingState();
    if (feed == null) {
      return PageState(
        title: '动态加载失败',
        message: errorMessage(value.error),
        onRetry: notifier.refresh,
      );
    }
    return RefreshIndicator(
      onRefresh: notifier.refresh,
      child: CustomScrollView(
        key: const PageStorageKey('related-feed'),
        controller: _scroll,
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          const SliverToBoxAdapter(
            child: Padding(
              padding: EdgeInsets.fromLTRB(18, 12, 18, 14),
              child: Text(
                '同行与圈子分享的瞬间与时光',
                style: TextStyle(fontWeight: FontWeight.w600, fontSize: 12),
              ),
            ),
          ),
          if (feed.refreshError case final error?)
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 18),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        '刷新失败：${errorMessage(error)}',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Tokens.muted,
                        ),
                      ),
                    ),
                    TextButton(
                      onPressed: notifier.refresh,
                      child: const Text('重试'),
                    ),
                  ],
                ),
              ),
            ),
          if (feed.items.isEmpty)
            const SliverFillRemaining(
              hasScrollBody: false,
              child: PageState(
                title: '还没有与你有关的动态',
                message: '加入同行或圈子后，新的分享会出现在这里',
              ),
            )
          else ...[
            _RelatedList(items: feed.items),
            SliverToBoxAdapter(
              child: _FeedFooter(feed: feed, onLoadMore: notifier.loadMore),
            ),
          ],
        ],
      ),
    );
  }
}

/// 单列按分享顺序阅读，每条动态以细分隔线区分。
class _RelatedList extends StatelessWidget {
  const _RelatedList({required this.items});
  final List<RelatedEntry> items;

  @override
  Widget build(BuildContext context) => SliverList.builder(
    itemCount: items.length,
    itemBuilder: (context, index) {
      final entry = items[index];
      return RelatedCard(key: ValueKey(entry.id), entry: entry);
    },
  );
}

class _FeedFooter extends StatelessWidget {
  const _FeedFooter({required this.feed, required this.onLoadMore});
  final RelatedFeedState feed;
  final VoidCallback onLoadMore;

  @override
  Widget build(BuildContext context) {
    if (feed.loadingMore) {
      return const Padding(
        padding: EdgeInsets.all(14),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (feed.moreError case final error?) {
      return Column(
        children: [
          Text(
            errorMessage(error),
            style: const TextStyle(color: Tokens.muted, fontSize: 12),
          ),
          TextButton(onPressed: onLoadMore, child: const Text('加载失败，点击重试')),
        ],
      );
    }
    if (feed.hasMore) {
      return Center(
        child: TextButton(onPressed: onLoadMore, child: const Text('加载更多')),
      );
    }
    return const Padding(
      padding: EdgeInsets.all(12),
      child: Center(
        child: Text('已经到底了', style: TextStyle(color: Tokens.muted)),
      ),
    );
  }
}
