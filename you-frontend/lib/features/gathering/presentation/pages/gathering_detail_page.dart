import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../app/theme/app_theme.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../models/gathering.dart';
import '../viewmodels/gathering_detail_viewmodel.dart';
import '../../models/gathering_lifecycle_action.dart';
import '../viewmodels/gathering_lifecycle_viewmodel.dart';
import '../widgets/gathering_actions.dart';
import '../widgets/gathering_card.dart';

/// 同行基本信息，提供成员列表和创建者编辑入口。
class GatheringDetailPage extends ConsumerWidget {
  const GatheringDetailPage({super.key, required this.id});

  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = gatheringDetailProvider(id);
    final value = ref.watch(provider);
    final command = ref.watch(gatheringLifecycleProvider(id));
    final busy = command.isLoading;
    return PopScope(
      canPop: !busy,
      child: AbsorbPointer(
        absorbing: busy,
        child: Scaffold(
          appBar: AppBar(
            title: const Text('同行信息'),
            actions: [
              GatheringActions(
                id: id,
                busy: busy,
                onAction: (action) => _runAction(context, ref, action),
              ),
              IconButton(
                tooltip: '刷新同行信息',
                onPressed: value.isLoading
                    ? null
                    : () => ref.invalidate(provider),
                icon: const Icon(Icons.refresh),
              ),
            ],
          ),
          body: Column(
            children: [
              if (busy) const LinearProgressIndicator(),
              Expanded(
                child: value.when(
                  loading: () => const LoadingState(),
                  error: (error, _) => PageState(
                    title: '暂时无法查看同行',
                    message: errorMessage(error),
                    onRetry: () => ref.invalidate(provider),
                  ),
                  data: (gathering) =>
                      _GatheringInformation(gathering: gathering),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 确认影响后执行动作；结束保留详情，退出和解散回到本人同行列表。
  Future<void> _runAction(
    BuildContext context,
    WidgetRef ref,
    GatheringLifecycleAction action,
  ) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('${action.label}？'),
        content: Text(action.confirmation),
        actions: [
          TextButton(
            onPressed: () => context.pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => context.pop(true),
            child: Text('确认${action.label}'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;
    final provider = gatheringLifecycleProvider(id);
    final success = await ref.read(provider.notifier).execute(action);
    if (!context.mounted) return;
    if (!success) {
      final error = ref.read(provider).error;
      if (error != null) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(errorMessage(error))));
      }
      return;
    }
    final messenger = ScaffoldMessenger.of(context);
    if (action != GatheringLifecycleAction.end) context.go('/gatherings');
    messenger.showSnackBar(SnackBar(content: Text(action.successMessage)));
  }
}

class _GatheringInformation extends StatelessWidget {
  const _GatheringInformation({required this.gathering});

  final Gathering gathering;

  @override
  Widget build(BuildContext context) {
    final description = gathering.description;
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        const Align(
          alignment: Alignment.centerLeft,
          child: CircleAvatar(
            radius: 30,
            backgroundColor: Tokens.green,
            child: Icon(Icons.people_outline, color: Tokens.accent, size: 30),
          ),
        ),
        const SizedBox(height: 24),
        Text(gathering.name, style: Theme.of(context).textTheme.headlineMedium),
        Text(gathering.status.label),
        const SizedBox(height: 16),
        FilledButton.icon(
          onPressed: () => context.push('/gatherings/${gathering.id}/moments'),
          icon: const Icon(Icons.photo_library_outlined),
          label: const Text('浏览共同记录'),
        ),
        const SizedBox(height: 8),
        if (gathering.status == GatheringStatus.ongoing)
          OutlinedButton.icon(
            onPressed: () =>
                context.push('/gatherings/${gathering.id}/moments/add'),
            icon: const Icon(Icons.add_photo_alternate_outlined),
            label: const Text('添加本人瞬间'),
          ),
        const SizedBox(height: 20),
        GatheringCreator(creator: gathering.creator),
        const SizedBox(height: 24),
        GatheringCounts(
          memberCount: gathering.memberCount,
          momentCount: gathering.momentCount,
        ),
        const SizedBox(height: 12),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
            onPressed: () =>
                context.push('/gatherings/${gathering.id}/members'),
            icon: const Icon(Icons.people_outline),
            label: const Text('查看成员'),
          ),
        ),
        if (gathering.status == GatheringStatus.ongoing)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: () =>
                  context.push('/gatherings/${gathering.id}/invite'),
              icon: const Icon(Icons.qr_code_2),
              label: const Text('邀请同行'),
            ),
          ),
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 20),
          child: Divider(),
        ),
        Text('关于这次同行', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 12),
        SelectableText(
          description == null || description.isEmpty ? '还没有填写介绍' : description,
        ),
        const SizedBox(height: 28),
        Text(
          '创建于 ${_formatDate(gathering.createdAt)}',
          style: const TextStyle(color: Tokens.muted),
        ),
      ],
    );
  }

  String _formatDate(DateTime value) {
    final local = value.toLocal();
    return '${local.year} 年 ${local.month} 月 ${local.day} 日';
  }
}
