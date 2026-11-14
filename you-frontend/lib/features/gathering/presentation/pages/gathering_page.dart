import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/network/api_client.dart';
import '../../../../shared/models/cursor_page.dart';
import '../../../../shared/ui/page_state.dart';
import '../../models/gathering.dart';
import '../viewmodels/gathering_list_viewmodel.dart';
import '../widgets/gathering_card.dart';

/// 本人同行入口，首页框架与底部导航由 AppShell 提供。
class GatheringPage extends ConsumerWidget {
  const GatheringPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final value = ref.watch(gatheringListProvider);
    final viewModel = ref.read(gatheringListProvider.notifier);
    return value.when(
      loading: () => const LoadingState(),
      error: (error, _) => PageState(
        title: '暂时无法查看同行',
        message: errorMessage(error),
        onRetry: viewModel.refresh,
      ),
      data: (data) => RefreshIndicator(
        onRefresh: viewModel.refresh,
        child: _GatheringList(data: data),
      ),
    );
  }
}

/// 使用惰性列表展示卡片和分页入口，保留已有页的浏览位置。
class _GatheringList extends StatelessWidget {
  const _GatheringList({required this.data});

  final BrowseState<GatheringSummary, GatheringCursor> data;

  @override
  Widget build(BuildContext context) => ListView.builder(
    key: const PageStorageKey('gathering-list'),
    physics: const AlwaysScrollableScrollPhysics(),
    padding: const EdgeInsets.fromLTRB(18, 20, 18, 32),
    itemCount: data.items.length + 2,
    itemBuilder: (context, index) {
      if (index == 0) return const _ListHeader();
      if (index == data.items.length + 1) {
        return _ListFooter(data: data);
      }
      final gathering = data.items[index - 1];
      return Padding(
        padding: const EdgeInsets.only(bottom: 14),
        child: GatheringCard(
          key: ValueKey(gathering.id),
          gathering: gathering,
          onTap: () => context.push('/gatherings/${gathering.id}'),
        ),
      );
    },
  );
}

class _ListHeader extends StatelessWidget {
  const _ListHeader();

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 24),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('一起经历的日子', style: Theme.of(context).textTheme.headlineMedium),
        const SizedBox(height: 8),
        const Text('把共同的瞬间，留在同一段经历里。'),
        const SizedBox(height: 20),
        Wrap(
          spacing: 12,
          runSpacing: 8,
          children: [
            FilledButton.icon(
              onPressed: () => context.push('/gatherings/new'),
              icon: const Icon(Icons.add),
              label: const Text('创建同行'),
            ),
            OutlinedButton.icon(
              onPressed: () => context.push('/gatherings/face-to-face'),
              icon: const Icon(Icons.near_me_outlined),
              label: const Text('面对面'),
            ),
            OutlinedButton.icon(
              onPressed: () => context.push('/gatherings/join'),
              icon: const Icon(Icons.group_add_outlined),
              label: const Text('加入同行'),
            ),
          ],
        ),
      ],
    ),
  );
}

class _ListFooter extends ConsumerWidget {
  const _ListFooter({required this.data});

  final BrowseState<GatheringSummary, GatheringCursor> data;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (data.items.isEmpty) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 36),
        child: Text('还没有同行，从创建一段共同经历开始。'),
      );
    }
    if (data.loadingMore) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (!data.hasMore) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: Center(child: Text('已展示全部同行')),
      );
    }
    final error = data.error;
    return Column(
      children: [
        if (error != null) Text(errorMessage(error)),
        TextButton(
          onPressed: ref.read(gatheringListProvider.notifier).loadMore,
          child: Text(error == null ? '加载更多' : '重试加载'),
        ),
      ],
    );
  }
}
