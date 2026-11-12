import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../viewmodels/album_browse_viewmodel.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../viewmodels/album_viewmodels.dart';
import '../widgets/album_cover.dart';
import '../widgets/album_timeline_gallery.dart';
import '../widgets/album_moments_sheet.dart';
import '../../../../shared/ui/mood_badge.dart';
import '../../../../shared/models/moment_mood.dart';

class AlbumDetailPage extends ConsumerWidget {
  const AlbumDetailPage({super.key, required this.id});
  final String id;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = albumDetailProvider(id);
    final value = ref.watch(provider);
    final deletion = ref.watch(albumDeleteProvider(id));
    Future<void> delete() async {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('删除这段时光？'),
          content: const Text('删除后无法恢复，时光中的原瞬间会保留。'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('删除时光'),
            ),
          ],
        ),
      );
      if (confirmed != true || !context.mounted) return;
      final deleted = await ref.read(albumDeleteProvider(id).notifier).delete();
      if (!context.mounted) return;
      if (deleted) {
        context.go('/albums');
      } else {
        final error = ref.read(albumDeleteProvider(id)).error;
        if (error != null) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text(errorMessage(error))));
        }
      }
    }

    Future<void> refresh() async {
      ref.invalidate(albumTimelineProvider(id));
      ref.invalidate(provider);
      try {
        await ref.read(provider.future);
      } catch (_) {}
    }

    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        title: const Text('时光信息'),
        backgroundColor: Colors.transparent,
        elevation: 0,
        actions: [
          IconButton(
            tooltip: '设置封面',
            onPressed: value.asData == null || deletion.busy
                ? null
                : () => context.push('/albums/$id/edit'),
            icon: const Icon(Icons.image_outlined),
          ),
          IconButton(
            tooltip: '分享时光到圈子',
            onPressed: value.asData == null || deletion.busy
                ? null
                : () => context.push('/circles/share/album/$id'),
            icon: const Icon(Icons.ios_share_outlined),
          ),
          PopupMenuButton<String>(
            tooltip: '管理时光',
            enabled: value.asData != null && !deletion.busy,
            onSelected: (action) {
              if (action == 'edit') {
                context.push('/albums/$id/edit');
              } else if (action == 'add' || action == 'remove') {
                showAlbumMomentsSheet(
                  context,
                  id: id,
                  removing: action == 'remove',
                );
              } else {
                delete();
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'add', child: Text('批量添加瞬间')),
              PopupMenuItem(value: 'remove', child: Text('批量移出瞬间')),
              PopupMenuItem(value: 'edit', child: Text('修改时光信息')),
              PopupMenuItem(value: 'delete', child: Text('删除时光')),
            ],
          ),
          if (deletion.busy)
            const Padding(
              padding: EdgeInsets.all(16),
              child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
        ],
      ),
      body: value.when(
        loading: () => const LoadingState(),
        error: (e, _) => PageState(
          title: '暂时无法查看时光',
          message: errorMessage(e),
          onRetry: refresh,
        ),
        data: (album) => RefreshIndicator(
          onRefresh: refresh,
          child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: EdgeInsets.zero,
            children: [
              SizedBox(
                height: 370,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    AlbumCover(url: album.coverUrl, height: 370, radius: 0),
                    DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          stops: [0.2, 0.6, 1],
                          colors: [
                            Colors.transparent,
                            Color(0x55FFFFFF),
                            Theme.of(context).colorScheme.surface,
                          ],
                        ),
                      ),
                    ),
                    Positioned(
                      left: 22,
                      right: 22,
                      bottom: 14,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '${album.momentCount} 个瞬间',
                            style: const TextStyle(fontSize: 11),
                          ),
                          const SizedBox(height: 10),
                          Text(
                            album.name,
                            style: const TextStyle(
                              fontSize: 27,
                              height: 1.4,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              if (album.description?.isNotEmpty == true)
                Padding(
                  padding: const EdgeInsets.all(22),
                  child: Text(album.description!),
                ),
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 20, 24, 32),
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    border: Border(
                      left: BorderSide(color: Theme.of(context).dividerColor),
                    ),
                  ),
                  child: _AlbumTimeline(id: id),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AlbumTimeline extends ConsumerWidget {
  const _AlbumTimeline({required this.id});
  final String id;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = albumTimelineProvider(id);
    return ref
        .watch(provider)
        .when(
          loading: () => const LoadingState(),
          error: (e, _) => PageState(
            title: '暂时无法加载瞬间',
            message: errorMessage(e),
            onRetry: () => ref.invalidate(provider),
          ),
          data: (data) => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (data.items.isEmpty) const Text('还没有瞬间，故事正慢慢展开。'),
              for (final item in data.items)
                Padding(
                  padding: const EdgeInsets.only(bottom: 30, left: 22),
                  child: InkWell(
                    onTap: () => context.push('/moments/${item.id}'),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '${_date(item.memoryTime.toIso8601String())}${item.place == null ? '' : ' · ${item.place}'}',
                          style: Theme.of(context).textTheme.labelSmall,
                        ),
                        const SizedBox(height: 12),
                        if (item.title != null)
                          Padding(
                            padding: const EdgeInsets.only(top: 12),
                            child: Text(
                              item.title!,
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                          ),
                        if (item.mood != null)
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 8),
                            child: MoodBadge(momentMoodLabel(item.mood)!),
                          ),
                        if (item.tags.isNotEmpty)
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 8),
                            child: Text(
                              item.tags.join(' · '),
                              style: Theme.of(context).textTheme.labelSmall,
                            ),
                          ),
                        if (item.images.isNotEmpty)
                          AlbumTimelineGallery(
                            key: ValueKey(item.id),
                            images: item.images,
                          ),
                        if (item.body != null)
                          Padding(
                            padding: const EdgeInsets.only(top: 10),
                            child: Text(item.body!),
                          ),
                      ],
                    ),
                  ),
                ),
              if (data.error != null) Text(errorMessage(data.error!)),
              if (data.loadingMore)
                const CircularProgressIndicator()
              else if (data.hasMore)
                TextButton(
                  onPressed: () => ref.read(provider.notifier).loadMore(),
                  child: Text(data.error == null ? '加载更多' : '重试'),
                ),
            ],
          ),
        );
  }

  String _date(String value) {
    final t = DateTime.parse(value).toLocal();
    return '${t.year}.${t.month.toString().padLeft(2, '0')}.${t.day.toString().padLeft(2, '0')}';
  }
}
