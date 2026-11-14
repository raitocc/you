import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/network/api_client.dart';
import '../../../../shared/models/cursor_page.dart';
import '../../../../shared/ui/page_state.dart';
import '../../models/gathering.dart';
import '../viewmodels/gathering_list_viewmodel.dart';
import '../viewmodels/moment_gathering_association_viewmodel.dart';

typedef GatheringSelection = ({String? id});

/// 发布返回选择结果；本人详情入口则确认关联已有瞬间。
class GatheringPickerPage extends ConsumerWidget {
  const GatheringPickerPage({super.key, this.selectedId, this.momentId});
  final String? selectedId;
  final String? momentId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final value = ref.watch(gatheringListProvider);
    final vm = ref.read(gatheringListProvider.notifier);
    final id = momentId;
    final association = id == null
        ? null
        : ref.watch(momentGatheringAssociationProvider(id));
    final busy = association?.isLoading == true;
    return PopScope(
      canPop: !busy,
      child: Scaffold(
        appBar: AppBar(title: const Text('选择同行')),
        body: value.when(
          loading: () => const LoadingState(),
          error: (error, _) => PageState(
            title: '同行读取失败',
            message: errorMessage(error),
            onRetry: vm.refresh,
          ),
          data: (data) => RefreshIndicator(
            onRefresh: vm.refresh,
            child: _GatheringChoices(
              data: data,
              selectedId: selectedId,
              busy: busy,
              existingMoment: id != null,
              error: association?.error,
              onLoadMore: vm.loadMore,
              onClear: () => context.pop<GatheringSelection>((id: null)),
              // 使用页面 context，数据刷新后仍能完成返回导航。
              onPick: (id) => _select(context, ref, id),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _select(
    BuildContext context,
    WidgetRef ref,
    String gatheringId,
  ) async {
    final id = momentId;
    if (id == null) {
      context.pop<GatheringSelection>((id: gatheringId));
      return;
    }
    final success = await ref
        .read(momentGatheringAssociationProvider(id).notifier)
        .associate(gatheringId);
    if (success && context.mounted) context.pop();
  }
}

class _GatheringChoices extends StatelessWidget {
  const _GatheringChoices({
    required this.data,
    required this.selectedId,
    required this.busy,
    required this.existingMoment,
    required this.error,
    required this.onPick,
    required this.onClear,
    required this.onLoadMore,
  });
  final BrowseState<GatheringSummary, GatheringCursor> data;
  final String? selectedId;
  final bool busy;
  final bool existingMoment;
  final Object? error;
  final ValueChanged<String> onPick;
  final VoidCallback onClear;
  final VoidCallback onLoadMore;

  @override
  Widget build(BuildContext context) => ListView.builder(
    physics: const AlwaysScrollableScrollPhysics(),
    itemCount: data.items.length + 2,
    itemBuilder: (context, index) {
      if (index == 0) return _header();
      if (index == data.items.length + 1) return _footer();
      final item = data.items[index - 1];
      return ListTile(
        title: Text(item.name),
        subtitle: Text(
          '${item.status.label} · ${item.memberCount} 人 · ${item.momentCount} 个瞬间',
        ),
        trailing: selectedId == item.id ? const Icon(Icons.check) : null,
        onTap: busy || item.status == GatheringStatus.ended
            ? null
            : () => onPick(item.id),
      );
    },
  );

  Widget _header() {
    if (!existingMoment) {
      return ListTile(
        title: const Text('不关联同行'),
        trailing: selectedId == null ? const Icon(Icons.check) : null,
        onTap: onClear,
      );
    }
    return Column(
      children: [
        if (busy) const LinearProgressIndicator(),
        if (error != null)
          Padding(
            padding: const EdgeInsets.all(16),
            child: Text(errorMessage(error)),
          ),
        const Padding(
          padding: EdgeInsets.all(16),
          child: Text('选择一个同行，关联当前瞬间。'),
        ),
      ],
    );
  }

  Widget _footer() => Column(
    children: [
      if (data.items.isEmpty)
        const Padding(padding: EdgeInsets.all(24), child: Text('还没有加入同行')),
      if (data.error != null) Text(errorMessage(data.error)),
      if (data.loadingMore) const CircularProgressIndicator(),
      if (data.hasMore && !data.loadingMore)
        TextButton(
          onPressed: busy ? null : onLoadMore,
          child: Text(data.error == null ? '加载更多同行' : '重试加载同行'),
        ),
    ],
  );
}
