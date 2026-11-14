import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../models/gathering_lifecycle_action.dart';
import '../../models/gathering.dart';
import '../viewmodels/gathering_detail_viewmodel.dart';
import '../viewmodels/gathering_edit_viewmodel.dart';

/// 创建者管理信息、结束和解散；普通成员通过退出离开。
class GatheringActions extends ConsumerWidget {
  const GatheringActions({
    super.key,
    required this.id,
    required this.busy,
    required this.onAction,
  });
  final String id;
  final bool busy;
  final ValueChanged<GatheringLifecycleAction> onAction;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final access = ref.watch(gatheringCanEditProvider(id));
    if (access.hasError) {
      return IconButton(
        tooltip: '操作权限读取失败，点击重试',
        onPressed: busy
            ? null
            : () {
                ref.invalidate(gatheringCurrentUserIdProvider);
                ref.invalidate(gatheringCanEditProvider(id));
              },
        icon: const Icon(Icons.sync_problem),
      );
    }
    final gathering = ref.watch(gatheringDetailProvider(id)).asData?.value;
    final isCreator = access.asData?.value;
    if (isCreator == null) return const SizedBox.shrink();
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (isCreator)
          IconButton(
            tooltip: '修改同行信息',
            onPressed: busy ? null : () => context.push('/gatherings/$id/edit'),
            icon: const Icon(Icons.edit_outlined),
          ),
        PopupMenuButton<GatheringLifecycleAction>(
          tooltip: '同行操作',
          enabled: !busy,
          onSelected: onAction,
          itemBuilder: (_) => [
            if (isCreator) ...[
              if (gathering?.status == GatheringStatus.ongoing)
                _item(GatheringLifecycleAction.end),
              _item(GatheringLifecycleAction.dissolve),
            ] else
              _item(GatheringLifecycleAction.leave),
          ],
        ),
      ],
    );
  }

  PopupMenuItem<GatheringLifecycleAction> _item(
    GatheringLifecycleAction action,
  ) => PopupMenuItem(value: action, child: Text(action.label));
}
