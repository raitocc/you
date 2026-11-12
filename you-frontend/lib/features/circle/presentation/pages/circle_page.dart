import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../models/circle.dart';
import '../viewmodels/circle_viewmodels.dart';
import '../widgets/circle_widgets.dart';

class CirclePage extends ConsumerStatefulWidget {
  const CirclePage({super.key, this.initialCircle});
  final String? initialCircle;
  @override
  ConsumerState<CirclePage> createState() => _CirclePageState();
}

class _CirclePageState extends ConsumerState<CirclePage> {
  late String? selected = widget.initialCircle;

  @override
  Widget build(BuildContext context) {
    final circles = ref.watch(circlesProvider);
    return Column(
      children: [
        SizedBox(
          height: 106,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            children: [
              _story(
                label: '新的圈子',
                avatar: const Icon(Icons.add),
                onTap: () => context.push('/circles/new'),
              ),
              if (circles.asData?.value case final page?) ...[
                for (final circle in page.items)
                  _story(
                    label: circle.name,
                    avatar: CircleProfileAvatar(circle.avatarUrl),
                    active: circle.id == _active(page.items),
                    onTap: () => setState(() => selected = circle.id),
                    details: () => context.push('/circles/${circle.id}'),
                  ),
                CircleMore(
                  busy: page.loadingMore,
                  hasMore: page.hasMore,
                  error: page.error,
                  load: ref.read(circlesProvider.notifier).loadMore,
                ),
              ],
            ],
          ),
        ),
        Expanded(
          child: circles.when(
            loading: () => const LoadingState(),
            error: (error, _) => PageState(
              title: '无法查看圈子',
              message: errorMessage(error),
              onRetry: () => ref.invalidate(circlesProvider),
            ),
            data: (page) {
              final id = _active(page.items);
              if (id == null) {
                return const PageState(
                  title: '还没有圈子',
                  message: '创建或加入圈子，和在意的人分享生活。',
                );
              }
              return _CircleFeed(key: ValueKey(id), id: id);
            },
          ),
        ),
      ],
    );
  }

  String? _active(List<CircleSummary> circles) =>
      circles.any((circle) => circle.id == selected)
      ? selected
      : circles.firstOrNull?.id;

  Widget _story({
    required String label,
    required Widget avatar,
    required VoidCallback onTap,
    bool active = false,
    VoidCallback? details,
  }) {
    final colors = Theme.of(context).colorScheme;
    return SizedBox(
      width: 86,
      child: Stack(
        alignment: Alignment.topCenter,
        children: [
          TextButton(
            onPressed: onTap,
            style: TextButton.styleFrom(padding: const EdgeInsets.all(3)),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 58,
                  height: 58,
                  padding: const EdgeInsets.all(3),
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: active ? colors.primary : colors.outlineVariant,
                      width: 2,
                    ),
                  ),
                  child: avatar,
                ),
                const SizedBox(height: 4),
                Text(label, maxLines: 1, overflow: TextOverflow.ellipsis),
              ],
            ),
          ),
          if (active && details != null)
            Positioned(
              right: 0,
              top: 0,
              child: IconButton(
                tooltip: '圈子详情',
                onPressed: details,
                icon: const Icon(Icons.more_horiz),
              ),
            ),
        ],
      ),
    );
  }
}

class _CircleFeed extends ConsumerStatefulWidget {
  const _CircleFeed({super.key, required this.id});
  final String id;
  @override
  ConsumerState<_CircleFeed> createState() => _CircleFeedState();
}

class _CircleFeedState extends ConsumerState<_CircleFeed> {
  bool withdrawing = false;
  Future<void> _withdraw(String shareId) async {
    if (withdrawing) return;
    setState(() => withdrawing = true);
    try {
      if (await confirmCircleAction(
            context,
            '撤回这条分享？',
            '圈内将不再显示这条分享，原内容会保留。',
          ) &&
          mounted) {
        await ref.read(circleActionsProvider).withdraw(widget.id, shareId);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(errorMessage(e))));
      }
    } finally {
      if (mounted) setState(() => withdrawing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final provider = circleFeedProvider(widget.id);
    return ref
        .watch(provider)
        .when(
          loading: () => const LoadingState(),
          error: (error, _) => PageState(
            title: '无法查看分享',
            message: errorMessage(error),
            onRetry: () => ref.invalidate(provider),
          ),
          data: (page) => RefreshIndicator(
            onRefresh: () async {
              try {
                ref.invalidate(provider);
                await ref.read(provider.future);
              } catch (_) {}
            },
            child: ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
              itemCount: page.items.length + 1,
              itemBuilder: (context, index) {
                if (index == page.items.length) {
                  return page.items.isEmpty
                      ? const Padding(
                          padding: EdgeInsets.all(24),
                          child: Center(child: Text('圈子里还没有分享')),
                        )
                      : CircleMore(
                          busy: page.loadingMore,
                          hasMore: page.hasMore,
                          error: page.error,
                          load: ref.read(provider.notifier).loadMore,
                        );
                }
                final share = page.items[index];
                return Card(
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        ListTile(
                          contentPadding: EdgeInsets.zero,
                          leading: CircleProfileAvatar(
                            share.sharer.avatarUrl,
                            radius: 18,
                          ),
                          title: Text(share.sharer.name),
                          subtitle: Text(
                            MaterialLocalizations.of(
                              context,
                            ).formatCompactDate(share.date.toLocal()),
                          ),
                          trailing: share.canWithdraw
                              ? IconButton(
                                  tooltip: '撤回分享',
                                  onPressed: withdrawing
                                      ? null
                                      : () => _withdraw(share.id),
                                  icon: const Icon(Icons.remove_circle_outline),
                                )
                              : null,
                        ),
                        if (share.moment case final moment?)
                          CircleMomentBody(moment),
                        if (share.album case final album?)
                          InkWell(
                            onTap: () => context.push(
                              '/circles/${widget.id}/shares/${share.id}',
                              extra: album.name,
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                if (album.cover case final cover?)
                                  CircleImage(cover),
                                ListTile(
                                  contentPadding: EdgeInsets.zero,
                                  title: Text(album.name),
                                  subtitle: Text('${album.count} 个瞬间 · 查看时光'),
                                  trailing: const Icon(Icons.arrow_forward),
                                ),
                                if (album.description case final description?)
                                  Text(description),
                              ],
                            ),
                          ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        );
  }
}
