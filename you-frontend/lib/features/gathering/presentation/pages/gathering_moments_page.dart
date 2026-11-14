import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../models/gathering.dart';
import '../viewmodels/gathering_detail_viewmodel.dart';
import 'package:go_router/go_router.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/models/cursor_page.dart';
import '../../../../shared/ui/page_state.dart';
import '../../../moment/models/moment.dart';
import '../../../moment/presentation/widgets/moment_card.dart';
import '../../models/gathering_moment.dart';
import '../viewmodels/gathering_moments_viewmodel.dart';
import '../widgets/gathering_moments_footer.dart';

/// 展示服务端正序的共同经历，点击卡片按同行权限读取完整瞬间。
class GatheringMomentsPage extends ConsumerWidget {
  const GatheringMomentsPage({super.key, required this.id});
  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = gatheringMomentsProvider(id);
    final value = ref.watch(provider);
    final vm = ref.read(provider.notifier);
    final gathering = ref.watch(gatheringDetailProvider(id)).asData?.value;
    return Scaffold(
      appBar: AppBar(
        title: const Text('共同记录'),
        actions: [
          PopupMenuButton<String>(
            tooltip: '管理同行瞬间',
            onSelected: (action) =>
                context.push('/gatherings/$id/moments/$action'),
            itemBuilder: (_) => [
              if (gathering?.status == GatheringStatus.ongoing)
                const PopupMenuItem(value: 'add', child: Text('添加本人瞬间')),
              const PopupMenuItem(value: 'remove', child: Text('移出本人瞬间')),
            ],
          ),
        ],
      ),
      body: value.when(
        loading: () => const LoadingState(),
        error: (error, _) => PageState(
          title: '共同记录读取失败',
          message: errorMessage(error),
          onRetry: vm.refresh,
        ),
        data: (data) => RefreshIndicator(
          onRefresh: vm.refresh,
          child: _Timeline(id: id, data: data, onLoadMore: vm.loadMore),
        ),
      ),
    );
  }
}

class _Timeline extends StatelessWidget {
  const _Timeline({
    required this.id,
    required this.data,
    required this.onLoadMore,
  });
  final String id;
  final BrowseState<GatheringMomentSummary, MomentFeedCursor> data;
  final VoidCallback onLoadMore;

  @override
  Widget build(BuildContext context) => ListView.builder(
    physics: const AlwaysScrollableScrollPhysics(),
    padding: const EdgeInsets.all(20),
    itemCount: data.items.length + 1,
    itemBuilder: (context, index) {
      if (index == data.items.length) {
        return GatheringMomentsFooter(data: data, onLoadMore: onLoadMore);
      }
      final item = data.items[index];
      return Padding(
        padding: const EdgeInsets.only(bottom: 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _Author(author: item.author),
            const SizedBox(height: 10),
            MomentCard(
              moment: item.moment,
              footer: '同行成员可见 · ${item.moment.imageCount} 张照片',
              onTap: () =>
                  context.push('/gatherings/$id/moments/${item.moment.id}'),
            ),
          ],
        ),
      );
    },
  );
}

class _Author extends StatelessWidget {
  const _Author({required this.author});
  final MomentAuthor? author;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      CircleAvatar(
        radius: 16,
        child: author?.avatarUrl == null
            ? const Icon(Icons.person_outline, size: 20)
            : ClipOval(
                child: SizedBox(
                  width: 32,
                  height: 32,
                  child: MomentNetworkImage(author!.avatarUrl!, label: '作者头像'),
                ),
              ),
      ),
      const SizedBox(width: 8),
      Expanded(child: Text(author?.nickname ?? '用户资料暂无')),
    ],
  );
}
