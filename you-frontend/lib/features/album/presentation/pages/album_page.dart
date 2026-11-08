import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../viewmodels/album_browse_viewmodel.dart';
import '../widgets/album_cover.dart';

class AlbumPage extends ConsumerWidget {
  const AlbumPage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final value = ref.watch(albumListProvider);
    Future<void> refresh() async {
      ref.invalidate(albumListProvider);
      await ref.read(albumListProvider.future);
    }

    return value.when(
      loading: () => const LoadingState(),
      error: (e, _) => PageState(
        title: '暂时无法查看时光',
        message: errorMessage(e),
        onRetry: refresh,
      ),
      data: (data) => RefreshIndicator(
        onRefresh: refresh,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(18, 20, 18, 32),
          children: [
            Text(
              '把瞬间，串成时光。',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: 8),
            const Text('那些不经意的记录，正在慢慢成为故事'),
            const SizedBox(height: 24),
            Row(
              children: [
                const Expanded(
                  child: Text(
                    '你的时光集',
                    style: TextStyle(fontWeight: FontWeight.w600),
                  ),
                ),
                TextButton.icon(
                  onPressed: () => context.push('/albums/new'),
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('手动创建时光'),
                ),
              ],
            ),
            if (data.items.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 48),
                child: Center(child: Text('给回忆起个名字，再慢慢放入瞬间。')),
              ),
            for (var i = 0; i < data.items.length; i++)
              Padding(
                padding: const EdgeInsets.only(bottom: 20),
                child: InkWell(
                  borderRadius: BorderRadius.circular(24),
                  onTap: () => context.push('/albums/${data.items[i].id}'),
                  child: i == 0
                      ? Stack(
                          children: [
                            AlbumCover(
                              url: data.items[i].coverUrl,
                              height: 275,
                              radius: 20,
                            ),
                            Positioned(
                              left: 0,
                              right: 0,
                              bottom: 0,
                              child: Container(
                                padding: const EdgeInsets.all(22),
                                decoration: BoxDecoration(
                                  gradient: const LinearGradient(
                                    begin: Alignment.topCenter,
                                    end: Alignment.bottomCenter,
                                    colors: [
                                      Colors.transparent,
                                      Color(0xB3182218),
                                    ],
                                  ),
                                  borderRadius: const BorderRadius.vertical(
                                    bottom: Radius.circular(24),
                                  ),
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      '${data.items[i].momentCount} 个瞬间',
                                      style: const TextStyle(
                                        color: Colors.white,
                                      ),
                                    ),
                                    const SizedBox(height: 8),
                                    Text(
                                      data.items[i].name,
                                      style: const TextStyle(
                                        fontSize: 26,
                                        fontWeight: FontWeight.w600,
                                        color: Colors.white,
                                      ),
                                    ),
                                    const SizedBox(height: 8),
                                    Text(
                                      '${data.items[i].createdAt.toLocal().year}.${data.items[i].createdAt.toLocal().month.toString().padLeft(2, '0')} — 至今',
                                      style: const TextStyle(
                                        color: Colors.white,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ],
                        )
                      : Row(
                          children: [
                            SizedBox(
                              width: 90,
                              child: AlbumCover(
                                url: data.items[i].coverUrl,
                                height: 100,
                                radius: 15,
                              ),
                            ),
                            const SizedBox(width: 16),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text('${data.items[i].momentCount} 个瞬间'),
                                  const SizedBox(height: 6),
                                  Text(
                                    data.items[i].name,
                                    style: Theme.of(
                                      context,
                                    ).textTheme.titleMedium,
                                  ),
                                  const SizedBox(height: 6),
                                  const Text(
                                    '每一个片段，都值得被记得',
                                    style: TextStyle(fontSize: 12),
                                  ),
                                ],
                              ),
                            ),
                            const Icon(Icons.arrow_forward, size: 18),
                          ],
                        ),
                ),
              ),
            if (data.error != null) Text(errorMessage(data.error!)),
            if (data.loadingMore)
              const Center(child: CircularProgressIndicator())
            else if (data.hasMore)
              TextButton(
                onPressed: () =>
                    ref.read(albumListProvider.notifier).loadMore(),
                child: Text(data.error == null ? '加载更多' : '重试'),
              ),
          ],
        ),
      ),
    );
  }
}
