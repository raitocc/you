import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../app/theme/app_theme.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../models/moment.dart';
import '../viewmodels/moment_feed_viewmodel.dart';
import '../widgets/moment_card.dart';

class MomentPage extends StatelessWidget {
  const MomentPage({super.key});

  @override
  Widget build(BuildContext context) => DefaultTabController(
    length: MomentTab.values.length,
    child: Column(
      children: [
        const SizedBox(
          width: double.infinity,
          child: TabBar(
            isScrollable: true,
            tabAlignment: TabAlignment.center,
            indicatorColor: Tokens.green,
            indicatorWeight: 2,
            dividerColor: Tokens.line,
            labelColor: Tokens.ink,
            unselectedLabelColor: Tokens.muted,
            tabs: [
              Tab(text: '你', height: 44),
              Tab(text: '与你有关', height: 44),
            ],
          ),
        ),
        Expanded(
          child: TabBarView(
            children: [for (final tab in MomentTab.values) _MomentFeed(tab)],
          ),
        ),
      ],
    ),
  );
}

class _MomentFeed extends ConsumerStatefulWidget {
  const _MomentFeed(this.tab);
  final MomentTab tab;

  @override
  ConsumerState<_MomentFeed> createState() => _MomentFeedState();
}

class _MomentFeedState extends ConsumerState<_MomentFeed>
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
    final provider = momentFeedProvider(widget.tab);
    if (ref.read(provider).asData?.value.moreError == null) {
      ref.read(provider.notifier).loadMore();
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
    final provider = momentFeedProvider(widget.tab);
    final value = ref.watch(provider);
    final feed = value.asData?.value;
    Future<void> refresh() async {
      try {
        ref.invalidate(provider);
        await ref.read(provider.future);
      } catch (_) {
        // 错误由信息流状态展示。
      }
    }

    if (feed == null && value.isLoading) return const LoadingState();
    if (feed == null) {
      return PageState(
        title: '加载失败',
        message: errorMessage(value.error),
        onRetry: refresh,
      );
    }

    return RefreshIndicator(
      onRefresh: refresh,
      child: CustomScrollView(
        key: PageStorageKey(widget.tab),
        controller: _scroll,
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(18, 12, 18, 14),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      widget.tab.description,
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: 12,
                      ),
                    ),
                  ),
                  Text(
                    '${feed.items.length} 个瞬间',
                    style: const TextStyle(color: Tokens.muted, fontSize: 11),
                  ),
                ],
              ),
            ),
          ),
          if (feed.items.isEmpty)
            SliverFillRemaining(
              hasScrollBody: false,
              child: PageState(
                title: widget.tab == MomentTab.yours ? '还没有瞬间' : '还没有与你有关的瞬间',
                message: widget.tab == MomentTab.yours
                    ? '记录今天想留下的事'
                    : '同行与圈子分享的记录将在这里相遇',
              ),
            )
          else
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              sliver: SliverCrossAxisGroup(
                slivers: [
                  for (var column = 0; column < 2; column++)
                    SliverPadding(
                      padding: EdgeInsets.only(
                        left: column == 0 ? 0 : 6,
                        right: column == 0 ? 6 : 0,
                      ),
                      sliver: SliverList.builder(
                        itemCount: (feed.items.length + 1 - column) ~/ 2,
                        itemBuilder: (context, index) {
                          final item = feed.items[index * 2 + column];
                          return Padding(
                            padding: const EdgeInsets.only(bottom: 18),
                            child: MomentCard(
                              key: ValueKey(item.id),
                              moment: item,
                              onTap: () => context.push('/moments/${item.id}'),
                            ),
                          );
                        },
                      ),
                    ),
                ],
              ),
            ),
          if (feed.items.isNotEmpty)
            SliverToBoxAdapter(
              child: Center(
                child: feed.loadingMore
                    ? const Padding(
                        padding: EdgeInsets.all(14),
                        child: CircularProgressIndicator(),
                      )
                    : feed.hasMore
                    ? TextButton(
                        onPressed: ref.read(provider.notifier).loadMore,
                        child: Text(
                          feed.moreError == null ? '加载更多' : '加载失败，点击重试',
                        ),
                      )
                    : const Padding(
                        padding: EdgeInsets.all(12),
                        child: Text(
                          '已经到底了',
                          style: TextStyle(color: Tokens.muted),
                        ),
                      ),
              ),
            ),
        ],
      ),
    );
  }
}
