import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../viewmodels/gathering_detail_viewmodel.dart';
import '../viewmodels/gathering_edit_viewmodel.dart';
import '../widgets/gathering_info_form.dart';

/// 加载基本信息并确认创建者身份，再回显可编辑表单。
class GatheringEditPage extends ConsumerWidget {
  const GatheringEditPage({super.key, required this.id});
  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final access = ref.watch(gatheringCanEditProvider(id));
    return access.when(
      loading: () => Scaffold(
        appBar: AppBar(title: const Text('修改同行信息')),
        body: const LoadingState(),
      ),
      error: (error, _) => Scaffold(
        appBar: AppBar(title: const Text('修改同行信息')),
        body: PageState(
          title: '暂时无法加载编辑信息',
          message: errorMessage(error),
          onRetry: () {
            ref.invalidate(gatheringCurrentUserIdProvider);
            ref.invalidate(gatheringDetailProvider(id));
          },
        ),
      ),
      data: (canEdit) => canEdit
          ? _editor(context, ref)
          : Scaffold(
              appBar: AppBar(title: const Text('修改同行信息')),
              body: const PageState(
                title: '仅创建者可修改',
                message: '你可以返回查看同行内容和成员。',
              ),
            ),
    );
  }

  /// 表单使用已加载的详情；成功保存后返回原页面。
  Widget _editor(BuildContext context, WidgetRef ref) {
    final gathering = ref.watch(gatheringDetailProvider(id)).requireValue;
    final state = ref.watch(gatheringEditProvider(id));
    return GatheringInfoForm(
      key: ValueKey(id),
      title: '修改同行信息',
      heading: '更新这次同行的介绍',
      description: '修改名称和介绍，让同行成员了解这段经历。',
      submitLabel: '保存修改',
      initialName: gathering.name,
      initialDescription: gathering.description ?? '',
      busy: state.isLoading,
      error: state.error,
      onSubmit: (name, description) async {
        final saved = await ref
            .read(gatheringEditProvider(id).notifier)
            .save(name, description);
        if (saved != null && context.mounted) context.pop();
      },
    );
  }
}
