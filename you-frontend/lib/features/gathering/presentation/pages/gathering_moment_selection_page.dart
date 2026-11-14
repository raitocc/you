import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/models/cursor_page.dart';
import '../../../../shared/ui/page_state.dart';
import '../../../moment/models/moment.dart';
import '../../../moment/presentation/widgets/moment_card.dart';
import '../../models/gathering_moment.dart';
import '../../models/gathering.dart';
import '../viewmodels/gathering_detail_viewmodel.dart';
import '../viewmodels/gathering_edit_viewmodel.dart';
import '../viewmodels/gathering_moment_selection_viewmodel.dart';
import '../widgets/gathering_moments_footer.dart';

/// 分页多选本人记录，提交失败保持选择，移出操作保留原瞬间。
class GatheringMomentSelectionPage extends ConsumerWidget {
  const GatheringMomentSelectionPage({
    super.key,
    required this.id,
    required this.removing,
  });
  final String id;
  final bool removing;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (!removing) {
      final provider = gatheringDetailProvider(id);
      final gathering = ref.watch(provider);
      if (!gathering.hasValue) {
        return Scaffold(
          appBar: AppBar(title: const Text('添加本人瞬间')),
          body: gathering.hasError
              ? PageState(
                  title: '同行读取失败',
                  message: errorMessage(gathering.error),
                  onRetry: () => ref.invalidate(provider),
                )
              : const LoadingState(),
        );
      }
      if (gathering.requireValue.status == GatheringStatus.ended) {
        return Scaffold(
          appBar: AppBar(title: const Text('添加本人瞬间')),
          body: const PageState(title: '同行已结束', message: '现有记录可继续浏览和移出。'),
        );
      }
    }
    final operation = (gatheringId: id, removing: removing);
    final selection = ref.watch(gatheringMomentSelectionProvider(operation));
    final vm = ref.read(gatheringMomentSelectionProvider(operation).notifier);
    final action = removing ? '移出' : '添加';
    return PopScope(
      canPop: !selection.busy,
      child: Scaffold(
        appBar: AppBar(title: Text('$action本人瞬间')),
        body: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                removing
                    ? '选择自己的记录移出同行，原瞬间会保留。'
                    : '可跨页多选。已关联其他同行时，整批提交会提示先移出原同行。',
              ),
            ),
            Expanded(
              child: _SelectionCandidates(
                operation: operation,
                selection: selection,
              ),
            ),
            if (selection.error != null)
              Padding(
                padding: const EdgeInsets.all(16),
                child: Text(
                  errorMessage(selection.error),
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                child: SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    onPressed: selection.busy || selection.selected.isEmpty
                        ? null
                        : () => _submit(context, vm),
                    child: Text(
                      selection.busy
                          ? '保存中…'
                          : '$action已选的 ${selection.selected.length} 个瞬间',
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _submit(
    BuildContext context,
    GatheringMomentSelectionViewModel vm,
  ) async {
    if (removing) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('移出选中的瞬间？'),
          content: const Text('这些记录将从同行移出，原瞬间和图片保留。'),
          actions: [
            TextButton(
              onPressed: () => context.pop(false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => context.pop(true),
              child: const Text('确认移出'),
            ),
          ],
        ),
      );
      if (confirmed != true || !context.mounted) return;
    }
    final success = await vm.submit();
    if (success && context.mounted) context.pop();
  }
}

/// 分别加载候选分页与本人身份，用顺序分支表达加载和错误状态。
class _SelectionCandidates extends ConsumerWidget {
  const _SelectionCandidates({
    required this.operation,
    required this.selection,
  });
  final GatheringMomentOperation operation;
  final GatheringMomentSelectionState selection;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    String? userId;
    if (operation.removing) {
      final identity = ref.watch(gatheringCurrentUserIdProvider);
      if (identity.isLoading) return const LoadingState();
      if (identity.hasError) {
        return PageState(
          title: '本人身份读取失败',
          message: errorMessage(identity.error),
          onRetry: () => ref.invalidate(gatheringCurrentUserIdProvider),
        );
      }
      userId = identity.requireValue;
    }
    final provider = gatheringMomentChoicesProvider(operation);
    final value = ref.watch(provider);
    return value.when(
      loading: () => const LoadingState(),
      error: (error, _) => PageState(
        title: '瞬间读取失败',
        message: errorMessage(error),
        onRetry: () => ref.invalidate(provider),
      ),
      data: (data) => _Choices(
        data: data,
        selected: selection.selected,
        busy: selection.busy,
        removing: operation.removing,
        userId: userId,
        onToggle: ref
            .read(gatheringMomentSelectionProvider(operation).notifier)
            .toggle,
        onLoadMore: ref.read(provider.notifier).loadMore,
      ),
    );
  }
}

class _Choices extends StatelessWidget {
  const _Choices({
    required this.data,
    required this.selected,
    required this.busy,
    required this.removing,
    required this.userId,
    required this.onToggle,
    required this.onLoadMore,
  });
  final BrowseState<GatheringMomentSummary, MomentFeedCursor> data;
  final Set<String> selected;
  final bool busy;
  final bool removing;
  final String? userId;
  final ValueChanged<String> onToggle;
  final VoidCallback onLoadMore;

  @override
  Widget build(BuildContext context) => ListView.builder(
    itemCount: data.items.length + 1,
    itemBuilder: (context, index) {
      if (index == data.items.length) {
        return GatheringMomentsFooter(
          data: data,
          onLoadMore: onLoadMore,
          enabled: !busy,
        );
      }
      final item = data.items[index];
      final moment = item.moment;
      final selectable =
          !removing || (userId != null && item.author?.id == userId);
      return CheckboxListTile(
        value: selected.contains(moment.id),
        onChanged: busy || !selectable ? null : (_) => onToggle(moment.id),
        title: Text(
          moment.title ?? moment.bodyPreview ?? '图片瞬间',
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
        subtitle: Text(
          selectable
              ? momentDate(moment.memoryTime)
              : item.author == null
              ? '用户资料暂无'
              : '${item.author!.nickname ?? "同行成员"}的瞬间',
        ),
        secondary: moment.coverUrl == null
            ? null
            : SizedBox(
                width: 48,
                height: 48,
                child: MomentNetworkImage(moment.coverUrl!),
              ),
      );
    },
  );
}
