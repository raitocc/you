import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../../moment/presentation/widgets/moment_detail_content.dart';
import '../viewmodels/shared_content_viewmodels.dart';

/// 圈子共享详情只提供查看、刷新和返回，沿用完整瞬间的展示组件。
class CircleSharedMomentDetailPage extends ConsumerWidget {
  const CircleSharedMomentDetailPage({super.key, required this.share});
  final SharedMomentKey share;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = circleSharedMomentProvider(share);
    final value = ref.watch(provider);
    return Scaffold(
      body: value.when(
        loading: () => Column(
          children: [
            AppBar(title: const Text('共享瞬间')),
            const Expanded(child: LoadingState()),
          ],
        ),
        error: (error, _) => Column(
          children: [
            AppBar(title: const Text('共享瞬间')),
            Expanded(
              child: PageState(
                title: '无法查看共享瞬间',
                message: errorMessage(error),
                onRetry: () => ref.invalidate(provider),
              ),
            ),
          ],
        ),
        data: (moment) => MomentDetailContent(
          moment: moment,
          visibilityLabel: '圈子成员可见',
          onRefresh: () => ref.invalidate(provider),
          nav: Row(
            children: [
              IconButton(
                tooltip: '返回',
                onPressed: () => context.pop(),
                icon: const Icon(Icons.arrow_back),
              ),
              const Spacer(),
              IconButton(
                tooltip: '刷新共享瞬间',
                onPressed: () => ref.invalidate(provider),
                icon: const Icon(Icons.refresh),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
