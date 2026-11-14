import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../viewmodels/gathering_create_viewmodel.dart';
import '../widgets/gathering_info_form.dart';

/// 创建页面连接表单与提交状态，成功后进入详情。
class GatheringCreatePage extends ConsumerWidget {
  const GatheringCreatePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(gatheringCreateProvider);
    return GatheringInfoForm(
      title: '创建同行',
      heading: '为这次经历起个名字',
      description: '旅行、聚会，或一起度过的某一天。',
      submitLabel: '创建同行',
      busy: state.isLoading,
      error: state.error,
      onSubmit: (name, description) async {
        final gathering = await ref
            .read(gatheringCreateProvider.notifier)
            .create(name, description);
        if (gathering != null && context.mounted) {
          context.replace('/gatherings/${gathering.id}');
        }
      },
    );
  }
}
