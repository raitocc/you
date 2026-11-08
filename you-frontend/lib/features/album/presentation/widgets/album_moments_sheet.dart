import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/network/api_client.dart';
import '../viewmodels/album_moments_viewmodel.dart';

Future<void> showAlbumMomentsSheet(
  BuildContext context, {
  required String id,
  required bool removing,
}) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  backgroundColor: Theme.of(context).colorScheme.surface,
  shape: const RoundedRectangleBorder(
    borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
  ),
  builder: (_) => FractionallySizedBox(
    heightFactor: .82,
    child: AlbumMomentsSheet(id: id, removing: removing),
  ),
);

class AlbumMomentsSheet extends ConsumerStatefulWidget {
  const AlbumMomentsSheet({
    super.key,
    required this.id,
    required this.removing,
  });
  final String id;
  final bool removing;
  @override
  ConsumerState<AlbumMomentsSheet> createState() => _SheetState();
}

class _SheetState extends ConsumerState<AlbumMomentsSheet> {
  AlbumMomentSelection get selection =>
      (id: widget.id, removing: widget.removing);
  @override
  void initState() {
    super.initState();
    Future.microtask(() {
      if (mounted) {
        ref.read(albumMomentsProvider(selection).notifier).loadMore();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final provider = albumMomentsProvider(selection);
    final state = ref.watch(provider);
    final vm = ref.read(provider.notifier);
    final action = widget.removing ? '移出' : '添加';
    return PopScope(
      canPop: !state.busy,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(22, 12, 22, 18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Center(
                child: Container(
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(
                    color: Theme.of(context).dividerColor,
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
              ),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '批量$action瞬间',
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                  ),
                  IconButton(
                    tooltip: '关闭',
                    onPressed: state.busy ? null : () => Navigator.pop(context),
                    icon: const Icon(Icons.close),
                  ),
                ],
              ),
              Text(widget.removing ? '只从这段时光移出，原瞬间会保留。' : '选择自己发布的瞬间，放入这段时光。'),
              const SizedBox(height: 12),
              Expanded(
                child: ListView(
                  children: [
                    for (final item in state.items)
                      CheckboxListTile(
                        contentPadding: EdgeInsets.zero,
                        controlAffinity: ListTileControlAffinity.trailing,
                        secondary: item.coverUrl == null
                            ? null
                            : ClipRRect(
                                borderRadius: BorderRadius.circular(12),
                                child: Image.network(
                                  item.coverUrl!,
                                  width: 54,
                                  height: 54,
                                  fit: BoxFit.cover,
                                  errorBuilder: (_, _, _) => const SizedBox(
                                    width: 54,
                                    height: 54,
                                    child: Icon(Icons.image_outlined),
                                  ),
                                ),
                              ),
                        title: Text(
                          item.title ?? item.preview ?? '图片瞬间',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                        value: state.selected.contains(item.id),
                        onChanged: state.busy
                            ? null
                            : (_) => vm.toggle(item.id),
                      ),
                    if (state.items.isEmpty &&
                        !state.loading &&
                        state.loadError == null)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 32),
                        child: Center(
                          child: Text(
                            widget.removing ? '这段时光还没有瞬间。' : '还没有可以添加的瞬间。',
                          ),
                        ),
                      ),
                    if (state.loading)
                      const Padding(
                        padding: EdgeInsets.all(16),
                        child: Center(child: CircularProgressIndicator()),
                      ),
                    if (state.loadError != null)
                      Text(errorMessage(state.loadError)),
                    if (state.hasMore && !state.loading)
                      TextButton(
                        onPressed: state.busy ? null : vm.loadMore,
                        child: Text(
                          state.loadError == null ? '加载更多瞬间' : '重新加载瞬间',
                        ),
                      ),
                  ],
                ),
              ),
              if (state.saveError != null)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Semantics(
                    liveRegion: true,
                    child: Text(
                      errorMessage(state.saveError),
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ),
                ),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed:
                      state.busy || state.loading || state.selected.isEmpty
                      ? null
                      : () async {
                          final success = await vm.submit();
                          if (success && context.mounted) {
                            Navigator.pop(context);
                          }
                        },
                  child: Text(
                    state.busy
                        ? '正在保存…'
                        : '$action已选的 ${state.selected.length} 个瞬间',
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
