import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../moment/presentation/widgets/moment_editor_fields.dart';
import '../viewmodels/gathering_detail_viewmodel.dart';

/// 编辑器展示所选同行名称，草稿只保存 ID，名称随详情更新。
class GatheringSelectionField extends ConsumerWidget {
  const GatheringSelectionField({
    super.key,
    required this.selectedId,
    required this.onTap,
  });
  final String? selectedId;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final id = selectedId;
    final value = id == null ? null : ref.watch(gatheringDetailProvider(id));
    final label = id == null
        ? '不关联同行'
        : value!.when(
            loading: () => '正在读取同行…',
            error: (_, _) => '同行信息读取失败，点击重新选择',
            data: (gathering) => gathering.name,
          );
    return MomentEditorRow(
      title: '关联同行',
      value: label,
      icon: Icons.groups_outlined,
      onTap: onTap,
    );
  }
}
