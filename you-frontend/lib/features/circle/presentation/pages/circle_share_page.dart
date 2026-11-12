import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../models/circle.dart';
import '../viewmodels/circle_viewmodels.dart';
import '../widgets/circle_widgets.dart';

class CircleSharePage extends ConsumerStatefulWidget {
  const CircleSharePage({super.key, required this.type, required this.id});
  final CircleShareType type;
  final String id;
  @override
  ConsumerState<CircleSharePage> createState() => _CircleSharePageState();
}

class _CircleSharePageState extends ConsumerState<CircleSharePage> {
  final targets = <String>{};
  bool busy = false;
  Object? error;

  Future<void> share() async {
    if (busy || targets.isEmpty) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await ref
          .read(circleActionsProvider)
          .share(widget.type, widget.id, targets);
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('已分享到圈子')));
      if (context.canPop()) {
        context.pop();
      } else {
        context.go('/circles');
      }
    } catch (e) {
      if (mounted) setState(() => error = e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(widget.type == CircleShareType.album ? '分享时光到圈子' : '分享瞬间到圈子'),
    ),
    body: ref
        .watch(circlesProvider)
        .when(
          loading: () => const LoadingState(),
          error: (e, _) => PageState(
            title: '无法查看圈子',
            message: errorMessage(e),
            onRetry: () => ref.invalidate(circlesProvider),
          ),
          data: (page) => ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (page.items.isEmpty)
                const ListTile(title: Text('创建或加入圈子后即可分享')),
              for (final circle in page.items)
                CheckboxListTile(
                  secondary: CircleProfileAvatar(circle.avatarUrl, radius: 18),
                  title: Text(circle.name),
                  value: targets.contains(circle.id),
                  onChanged: busy
                      ? null
                      : (checked) => setState(() {
                          if (checked == true) {
                            targets.add(circle.id);
                          } else {
                            targets.remove(circle.id);
                          }
                        }),
                ),
              CircleMore(
                busy: page.loadingMore,
                hasMore: page.hasMore,
                error: page.error,
                load: ref.read(circlesProvider.notifier).loadMore,
              ),
              if (error != null) Text(errorMessage(error)),
              if (busy) const LinearProgressIndicator(),
              FilledButton(
                onPressed: busy || targets.isEmpty ? null : share,
                child: const Text('确认分享'),
              ),
            ],
          ),
        ),
  );
}

class SharedTimelinePage extends ConsumerWidget {
  const SharedTimelinePage({super.key, required this.share, this.title});
  final SharedTimelineKey share;
  final String? title;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = sharedTimelineProvider(share);
    return Scaffold(
      appBar: AppBar(title: Text(title ?? '共享时光')),
      body: ref
          .watch(provider)
          .when(
            loading: () => const LoadingState(),
            error: (e, _) => PageState(
              title: '无法查看共享时光',
              message: errorMessage(e),
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
                padding: const EdgeInsets.all(20),
                itemCount: page.items.length + 1,
                itemBuilder: (context, index) {
                  if (index < page.items.length) {
                    return Padding(
                      padding: const EdgeInsets.only(bottom: 24),
                      child: CircleMomentBody(page.items[index]),
                    );
                  }
                  if (page.items.isEmpty) {
                    return const Center(child: Text('这段时光还没有瞬间'));
                  }
                  return CircleMore(
                    busy: page.loadingMore,
                    hasMore: page.hasMore,
                    error: page.error,
                    load: ref.read(provider.notifier).loadMore,
                  );
                },
              ),
            ),
          ),
    );
  }
}
