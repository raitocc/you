import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../app/theme/app_theme.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../../album/models/album.dart';
import '../../models/circle.dart';
import '../viewmodels/circle_viewmodels.dart';
import '../viewmodels/shared_content_viewmodels.dart';
import '../widgets/circle_widgets.dart';

/// 共享时光先读取完整信息，再展示现有分页时间线。
class SharedTimelinePage extends ConsumerWidget {
  const SharedTimelinePage({super.key, required this.share, this.title});
  final SharedTimelineKey share;
  final String? title;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = circleSharedAlbumProvider(share);
    final value = ref.watch(provider);
    return Scaffold(
      appBar: AppBar(title: Text(value.asData?.value.name ?? title ?? '共享时光')),
      body: value.when(
        loading: () => const LoadingState(),
        error: (error, _) => PageState(
          title: '无法查看共享时光',
          message: errorMessage(error),
          onRetry: () => ref.invalidate(provider),
        ),
        data: (album) => _SharedAlbumTimeline(share: share, album: album),
      ),
    );
  }
}

class _SharedAlbumTimeline extends ConsumerWidget {
  const _SharedAlbumTimeline({required this.share, required this.album});
  final SharedTimelineKey share;
  final Album album;

  /// 刷新同时更新时光信息和内部瞬间，失败由对应状态展示。
  Future<void> _refresh(WidgetRef ref) async {
    final details = circleSharedAlbumProvider(share);
    final timeline = sharedTimelineProvider(share);
    ref.invalidate(details);
    ref.invalidate(timeline);
    try {
      await Future.wait([ref.read(details.future), ref.read(timeline.future)]);
    } catch (_) {
      // 页面分别展示信息与时间线的读取错误，提供重试入口。
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = sharedTimelineProvider(share);
    final value = ref.watch(provider);
    return RefreshIndicator(
      onRefresh: () => _refresh(ref),
      child: CustomScrollView(
        key: PageStorageKey(share),
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverToBoxAdapter(child: _AlbumHeader(album: album)),
          ...value.when(
            loading: () => [
              const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.all(24),
                  child: LoadingState(),
                ),
              ),
            ],
            error: (error, _) => [
              SliverToBoxAdapter(
                child: PageState(
                  title: '瞬间加载失败',
                  message: errorMessage(error),
                  onRetry: () => ref.invalidate(provider),
                ),
              ),
            ],
            data: (page) => [
              if (page.items.isEmpty)
                const SliverFillRemaining(
                  hasScrollBody: false,
                  child: PageState(
                    title: '这段时光还没有瞬间',
                    message: '分享者添加的瞬间将在这里展示',
                  ),
                )
              else
                SliverList.builder(
                  itemCount: page.items.length,
                  itemBuilder: (context, index) =>
                      _TimelineMoment(share: share, moment: page.items[index]),
                ),
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: CircleMore(
                    busy: page.loadingMore,
                    hasMore: page.hasMore,
                    error: page.error,
                    load: ref.read(provider.notifier).loadMore,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _AlbumHeader extends StatelessWidget {
  const _AlbumHeader({required this.album});
  final Album album;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (album.coverUrl case final url?)
          Padding(
            padding: const EdgeInsets.only(bottom: 16),
            child: CircleImage(url),
          ),
        if (album.description case final description?
            when description.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Text(
              description,
              style: const TextStyle(fontSize: 14, height: 1.6),
            ),
          ),
        Text(
          '${album.momentCount} 个瞬间 · 圈子分享',
          style: const TextStyle(fontSize: 12, color: Tokens.muted),
        ),
      ],
    ),
  );
}

class _TimelineMoment extends StatelessWidget {
  const _TimelineMoment({required this.share, required this.moment});
  final SharedTimelineKey share;
  final CircleMoment moment;

  @override
  Widget build(BuildContext context) => InkWell(
    onTap: () => context.push(
      '/circles/${share.circleId}/shares/${share.shareId}/moments/${moment.id}',
    ),
    child: Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          CircleMomentBody(moment),
          const SizedBox(height: 12),
          const Text(
            '查看完整瞬间',
            style: TextStyle(color: Tokens.accent, fontSize: 12),
          ),
          const SizedBox(height: 16),
          const Divider(height: 1, color: Tokens.line),
        ],
      ),
    ),
  );
}
