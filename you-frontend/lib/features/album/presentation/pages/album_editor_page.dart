import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import '../../../../core/network/api_client.dart';
import '../viewmodels/album_viewmodels.dart';
import '../../models/album.dart';
import '../states/album_editor_state.dart';
import '../widgets/album_cover.dart';
import '../../../../shared/ui/page_state.dart';

class AlbumEditorPage extends ConsumerStatefulWidget {
  const AlbumEditorPage({super.key, this.album});
  final Album? album;
  @override
  ConsumerState<AlbumEditorPage> createState() => _EditorState();
}

class _EditorState extends ConsumerState<AlbumEditorPage> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController(), _description = TextEditingController();
  bool _picking = false;
  bool _coverCleared = false;
  NotifierProvider<AlbumEditorViewModel, AlbumEditorState> get _provider =>
      widget.album == null
      ? albumEditorProvider
      : albumEditProvider(widget.album!);
  @override
  void initState() {
    super.initState();
    _name.text = widget.album?.name ?? '';
    _description.text = widget.album?.description ?? '';
    Future.microtask(() {
      if (mounted && widget.album == null) {
        ref.read(_provider.notifier).loadMoments();
      }
    });
  }

  @override
  void dispose() {
    _name.dispose();
    _description.dispose();
    super.dispose();
  }

  Future<void> _pick() async {
    if (_picking) return;
    setState(() => _picking = true);
    try {
      final image = await ImagePicker().pickImage(source: ImageSource.gallery);
      if (image == null || !mounted) return;
      final bytes = await image.readAsBytes();
      final mime =
          image.mimeType ??
          switch (image.name.split('.').last.toLowerCase()) {
            'png' => 'image/png',
            'webp' => 'image/webp',
            'jpg' || 'jpeg' => 'image/jpeg',
            _ => 'application/octet-stream',
          };
      if (mounted) {
        await ref.read(_provider.notifier).cover(bytes, mime);
      }
    } catch (e) {
      if (mounted) ref.read(_provider.notifier).reportError(e);
    } finally {
      if (mounted) setState(() => _picking = false);
    }
  }

  Future<void> _submit() async {
    if (!_form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    final album = await ref
        .read(_provider.notifier)
        .create(_name.text, _description.text);
    if (album != null && mounted) {
      if (widget.album != null) {
        ref.invalidate(albumDetailProvider(album.id));
        context.pop();
      } else {
        context.replace('/albums/${album.id}');
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(_provider);
    final vm = ref.read(_provider.notifier);
    final busy = state.busy || _picking;
    return PopScope(
      canPop: !busy,
      child: Scaffold(
        appBar: AppBar(title: Text(widget.album == null ? '手动创建时光' : '修改时光信息')),
        bottomNavigationBar: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: FilledButton(
              onPressed: busy ? null : _submit,
              child: Text(
                busy
                    ? '正在保存…'
                    : widget.album == null
                    ? '创建时光'
                    : '保存修改',
              ),
            ),
          ),
        ),
        body: Form(
          key: _form,
          child: ListView(
            padding: const EdgeInsets.all(18),
            children: [
              Text(
                widget.album == null ? '给回忆，一个归处。' : '留住这段时光。',
                style: Theme.of(context).textTheme.headlineMedium,
              ),
              const SizedBox(height: 12),
              Text(
                widget.album == null ? '从一个名字开始，把想珍藏的瞬间放在一起。' : '更新名称、介绍与封面。',
              ),
              const SizedBox(height: 24),
              if (state.cover == null &&
                  !_coverCleared &&
                  widget.album?.coverUrl != null)
                AlbumCover(url: widget.album!.coverUrl, height: 180),
              if (state.cover != null)
                ClipRRect(
                  borderRadius: BorderRadius.circular(24),
                  child: Image.memory(
                    state.cover!,
                    height: 180,
                    fit: BoxFit.cover,
                  ),
                ),
              Wrap(
                spacing: 12,
                children: [
                  OutlinedButton.icon(
                    onPressed: busy ? null : _pick,
                    icon: const Icon(Icons.image_outlined),
                    label: Text(
                      state.cover == null &&
                              (_coverCleared ||
                                  widget.album?.coverFileId == null)
                          ? '选择封面（可选）'
                          : '更换封面',
                    ),
                  ),
                  if (state.cover != null ||
                      (!_coverCleared && widget.album?.coverFileId != null))
                    TextButton(
                      onPressed: busy
                          ? null
                          : () {
                              vm.clearCover();
                              setState(() => _coverCleared = true);
                            },
                      child: const Text('移除封面'),
                    ),
                ],
              ),
              const SizedBox(height: 24),
              TextFormField(
                controller: _name,
                enabled: !busy,
                decoration: const InputDecoration(
                  labelText: '时光名称',
                  helperText: '必填，最多 128 字符',
                ),
                validator: (v) => v == null || v.trim().isEmpty
                    ? '请填写时光名称'
                    : v.trim().runes.length > 128
                    ? '名称不能超过 128 字符'
                    : null,
              ),
              const SizedBox(height: 20),
              TextFormField(
                controller: _description,
                enabled: !busy,
                minLines: 3,
                maxLines: 6,
                decoration: const InputDecoration(
                  labelText: '时光介绍（可选）',
                  helperText: '最多 5000 字符',
                ),
                validator: (v) =>
                    (v ?? '').runes.length > 5000 ? '介绍不能超过 5000 字符' : null,
              ),
              if (widget.album == null) ...[
                const SizedBox(height: 32),
                Text(
                  '放入瞬间 · 已选 ${state.selected.length}',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 8),
                const Text('可暂时留空，以后再慢慢整理。'),
                const SizedBox(height: 12),
                for (final item in state.items)
                  CheckboxListTile(
                    contentPadding: EdgeInsets.zero,
                    value: state.selected.contains(item.id),
                    onChanged: busy ? null : (_) => vm.toggle(item.id),
                    title: Text(
                      item.title ?? item.preview ?? '图片瞬间',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                if (state.items.isEmpty &&
                    !state.loading &&
                    state.momentsError == null)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 24),
                    child: Text('还没有可以放入的瞬间，可以先创建空时光。'),
                  ),
                if (state.loading)
                  const Center(
                    child: Padding(
                      padding: EdgeInsets.all(16),
                      child: CircularProgressIndicator(),
                    ),
                  ),
                if (state.momentsError != null)
                  Text(errorMessage(state.momentsError)),
                if (state.hasMore && !state.loading)
                  TextButton(
                    onPressed: busy ? null : vm.loadMoments,
                    child: Text(
                      state.momentsError == null ? '加载更多瞬间' : '重新加载瞬间',
                    ),
                  ),
              ],
              if (state.error != null)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  child: Semantics(
                    liveRegion: true,
                    child: Text(
                      errorMessage(state.error),
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
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

class AlbumEditPage extends ConsumerWidget {
  const AlbumEditPage({super.key, required this.id});
  final String id;
  @override
  Widget build(BuildContext context, WidgetRef ref) => ref
      .watch(albumDetailProvider(id))
      .when(
        loading: () => const Scaffold(body: LoadingState()),
        error: (error, _) => Scaffold(
          appBar: AppBar(title: const Text('修改时光信息')),
          body: PageState(
            title: '暂时无法加载时光',
            message: errorMessage(error),
            onRetry: () => ref.invalidate(albumDetailProvider(id)),
          ),
        ),
        data: (album) => AlbumEditorPage(key: ValueKey(album), album: album),
      );
}
