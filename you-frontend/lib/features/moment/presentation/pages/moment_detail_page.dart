import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../data/moment_repository.dart';
import '../viewmodels/moment_detail_viewmodel.dart';
import '../widgets/moment_detail_content.dart';

class MomentDetailPage extends ConsumerStatefulWidget {
  const MomentDetailPage({super.key, required this.id});
  final String id;

  @override
  ConsumerState<MomentDetailPage> createState() => _MomentDetailPageState();
}

class _MomentDetailPageState extends ConsumerState<MomentDetailPage> {
  bool _deleting = false;

  void _back() {
    if (context.canPop()) {
      context.pop();
    } else {
      context.go('/moments');
    }
  }

  /// 确认删除操作并在成功后离开详情页。
  Future<void> _delete() async {
    final approved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除这个瞬间？'),
        content: const Text('删除后，它将从你的瞬间列表中移除。'),
        actions: [
          TextButton(
            onPressed: () => context.pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => context.pop(true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (approved != true || !mounted) return;
    setState(() => _deleting = true);
    final provider = momentDetailProvider(widget.id);
    try {
      await ref.read(provider.notifier).delete();
      if (mounted) _back();
    } catch (error) {
      if (!mounted) return;
      if (momentErrorCode(error) == 'MOMENT_NOT_FOUND') {
        ref.invalidate(provider);
      }
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(errorMessage(error))));
    } finally {
      if (mounted) setState(() => _deleting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final provider = momentDetailProvider(widget.id);
    final state = ref.watch(provider);
    final moment = state.asData?.value;
    final back = IconButton(
      tooltip: '返回',
      onPressed: _back,
      icon: const Icon(Icons.arrow_back_ios_new, size: 20),
    );
    if (moment == null) {
      return Scaffold(
        appBar: AppBar(leading: back),
        body: state.isLoading
            ? const LoadingState()
            : PageState(
                title: '无法查看瞬间',
                message: errorMessage(state.error),
                onRetry: () => ref.invalidate(provider),
              ),
      );
    }
    final actions = [
      if (moment.canEdit)
        IconButton(
          tooltip: '编辑瞬间',
          onPressed: _deleting
              ? null
              : () => context.push('/moments/${moment.id}/edit'),
          icon: const Icon(Icons.edit_outlined),
        ),
      if (moment.canEdit)
        IconButton(
          tooltip: '分享到圈子',
          onPressed: _deleting
              ? null
              : () => context.push('/circles/share/moment/${moment.id}'),
          icon: const Icon(Icons.ios_share_outlined),
        ),
      if (moment.canEdit)
        IconButton(
          tooltip: '关联同行',
          onPressed: _deleting
              ? null
              : () => context.push('/gatherings/select?momentId=${moment.id}'),
          icon: const Icon(Icons.group_outlined),
        ),
      if (moment.canDelete)
        PopupMenuButton<String>(
          tooltip: '更多操作',
          enabled: !_deleting,
          onSelected: (_) => _delete(),
          itemBuilder: (_) => const [
            PopupMenuItem(value: 'delete', child: Text('删除瞬间')),
          ],
        ),
    ];
    final nav = Row(children: [back, const Spacer(), ...actions]);
    return Scaffold(
      body: MomentDetailContent(
        moment: moment,
        nav: nav,
        busy: _deleting,
        onRefresh: () => ref.invalidate(provider),
      ),
    );
  }
}
