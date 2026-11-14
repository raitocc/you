import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../../moment/presentation/widgets/moment_detail_content.dart';
import '../viewmodels/gathering_edit_viewmodel.dart';
import '../viewmodels/gathering_moments_viewmodel.dart';

/// 同行路径中的详情保持只读，本人记录可另行打开本人详情编辑。
class GatheringMomentDetailPage extends ConsumerWidget {
  const GatheringMomentDetailPage({
    super.key,
    required this.gatheringId,
    required this.momentId,
  });
  final String gatheringId;
  final String momentId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = gatheringMomentDetailProvider((
      gatheringId: gatheringId,
      momentId: momentId,
    ));
    final value = ref.watch(provider);
    return Scaffold(
      body: value.when(
        loading: () => const LoadingState(),
        error: (error, _) => Column(
          children: [
            AppBar(title: const Text('同行瞬间')),
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
          visibilityLabel: '同行成员可见',
          onRefresh: () => ref.invalidate(provider),
          nav: Row(
            children: [
              IconButton(
                tooltip: '返回',
                onPressed: () => context.pop(),
                icon: const Icon(Icons.arrow_back),
              ),
              const Spacer(),
              _OwnMomentAction(
                authorId: moment.author?.id,
                momentId: moment.id,
              ),
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

/// 身份加载失败仅影响本人入口，保留共享内容并提供明确重试。
class _OwnMomentAction extends ConsumerWidget {
  const _OwnMomentAction({required this.authorId, required this.momentId});
  final String? authorId;
  final String momentId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (authorId == null) return const SizedBox.shrink();
    final identity = ref.watch(gatheringCurrentUserIdProvider);
    return identity.when(
      loading: () => const SizedBox.shrink(),
      error: (_, _) => IconButton(
        tooltip: '本人身份读取失败，点击重试',
        onPressed: () => ref.invalidate(gatheringCurrentUserIdProvider),
        icon: const Icon(Icons.sync_problem),
      ),
      data: (id) => id == authorId
          ? TextButton(
              onPressed: () => context.push('/moments/$momentId'),
              child: const Text('查看本人瞬间'),
            )
          : const SizedBox.shrink(),
    );
  }
}
