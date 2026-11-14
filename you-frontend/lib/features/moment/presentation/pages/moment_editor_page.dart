import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import '../../../../app/theme/app_theme.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../data/moment_repository.dart';
import '../../../gathering/presentation/pages/gathering_picker_page.dart';
import '../../../gathering/presentation/widgets/gathering_selection_field.dart';
import '../../models/moment.dart';
import '../../models/moment_draft.dart';
import '../states/moment_editor_state.dart';
import '../viewmodels/moment_editor_viewmodel.dart';
import '../widgets/moment_card.dart';
import '../widgets/moment_editor_fields.dart';
import '../widgets/moment_editor_sheets.dart';

class MomentEditorPage extends ConsumerStatefulWidget {
  const MomentEditorPage({super.key, this.id, this.focusSharing = false});
  final String? id;
  final bool focusSharing;

  @override
  ConsumerState<MomentEditorPage> createState() => _MomentEditorPageState();
}

class _MomentEditorPageState extends ConsumerState<MomentEditorPage> {
  bool _allowPop = false;
  bool _leaving = false;
  bool _focusedSharing = false;
  final _editorScroll = ScrollController();

  @override
  void dispose() {
    _editorScroll.dispose();
    super.dispose();
  }

  MomentEditorViewModel get _editor =>
      ref.read(momentEditorProvider(widget.id).notifier);

  void _leave([String? publishedId]) {
    if (!mounted) return;
    setState(() => _allowPop = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (publishedId != null) {
        context.pushReplacement('/moments/$publishedId');
      } else if (context.canPop()) {
        context.pop();
      } else {
        context.go(widget.id == null ? '/moments' : '/moments/${widget.id}');
      }
    });
  }

  /// 离开编辑页前处理未保存的草稿。
  Future<void> _requestBack(MomentEditorState? state) async {
    if (_leaving || state?.progress != null) return;
    if (state == null || !state.dirty) {
      _leave();
      return;
    }
    _leaving = true;
    try {
      if (state.draft.pending != null) {
        await _editor.saveDraft();
        _leave();
        return;
      }
      final choice = await showDialog<String>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('保留这次编辑？'),
          content: const Text('保存草稿后，下次打开可以继续编辑。'),
          actions: [
            TextButton(
              onPressed: () => context.pop(),
              child: const Text('继续编辑'),
            ),
            TextButton(
              onPressed: () => context.pop('discard'),
              child: const Text('放弃'),
            ),
            FilledButton(
              onPressed: () => context.pop('save'),
              child: const Text('保存草稿'),
            ),
          ],
        ),
      );
      if (!mounted || choice == null) return;
      if (choice == 'save') {
        await _editor.saveDraft();
      } else {
        await _editor.discard();
      }
      _leave();
    } catch (error) {
      _message(errorMessage(error));
    } finally {
      _leaving = false;
    }
  }

  void _message(String message) {
    if (mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(message)));
    }
  }

  /// 选择图片并交给编辑状态保存到本地。
  Future<void> _pickImages(int remaining) async {
    if (remaining == 0) {
      _message('一个瞬间最多添加 20 张照片');
      return;
    }
    try {
      final picker = ImagePicker();
      final files = remaining == 1
          ? [?await picker.pickImage(source: ImageSource.gallery)]
          : await picker.pickMultiImage(limit: remaining);
      if (!mounted || files.isEmpty) return;
      if (files.length > remaining) {
        _message('最多再添加 $remaining 张照片');
        return;
      }
      for (final file in files) {
        if (await file.length() > 10 * 1024 * 1024) {
          _message('每张图片不能超过 10 MiB');
          return;
        }
      }
      final bytes = [for (final file in files) await file.readAsBytes()];
      if (mounted) await _editor.addImages(bytes);
    } catch (error) {
      _message(errorMessage(error));
    }
  }

  /// 提交当前草稿，成功后携带结果退出编辑页。
  Future<void> _submit() async {
    FocusScope.of(context).unfocus();
    final result = await _editor.submit();
    if (result == null || !mounted) return;
    if (widget.id == null) {
      _leave(result.id);
    } else {
      _leave();
    }
  }

  Future<void> _pickDate(MomentDraft draft) async {
    final existing = (draft.time ?? DateTime.now()).toLocal();
    final day = await showMomentCalendar(context, existing);
    if (!mounted || day == null) return;
    _editor.updateDraft(
      (draft) => draft.copyWith(
        time: DateTime(
          day.year,
          day.month,
          day.day,
          existing.hour,
          existing.minute,
        ),
      ),
    );
  }

  List<MomentSource> _knownSources(MomentEditorState state) =>
      state.original?.sources ?? const [];

  /// 通过本人同行列表选择发布目标，草稿只保存同行 ID。
  Future<void> _pickGathering(MomentDraft draft) async {
    final selected = draft.gatheringId;
    final query = selected == null
        ? ''
        : '?selected=${Uri.encodeComponent(selected)}';
    final result = await context.push<GatheringSelection>(
      '/gatherings/select$query',
    );
    if (result != null && mounted) {
      _editor.updateDraft(
        (draft) => draft.copyWith(gatheringId: () => result.id),
      );
    }
  }

  Future<void> _pickCircles(MomentEditorState state) async {
    final result = await showMomentTargets(
      context,
      journey: false,
      selected: state.draft.circleIds,
      known: _knownSources(
        state,
      ).where((source) => source.type == 'circle').toList(),
    );
    if (result != null && mounted) {
      _editor.updateDraft((draft) => draft.copyWith(circleIds: result));
    }
  }

  @override
  Widget build(BuildContext context) {
    final provider = momentEditorProvider(widget.id);
    final async = ref.watch(provider);
    final state = async.asData?.value;
    final draft = state?.draft;
    final pending = draft?.pending != null;
    final busy = state?.progress != null;
    final editable = draft != null && !busy && !pending;
    if (widget.focusSharing && state != null && !_focusedSharing) {
      _focusedSharing = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _editorScroll.hasClients) {
          _editorScroll.jumpTo(_editorScroll.position.maxScrollExtent);
        }
      });
    }
    final conflict =
        state?.error != null &&
        momentErrorCode(state!.error!) == 'VERSION_CONFLICT';
    final colors = Theme.of(context).colorScheme;

    return PopScope(
      canPop: _allowPop,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _requestBack(state);
      },
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(
            tooltip: '返回',
            onPressed: busy ? null : () => _requestBack(state),
            icon: const Icon(Icons.arrow_back_ios_new, size: 20),
          ),
          centerTitle: true,
          title: Text(
            widget.id == null ? '新的瞬间' : '编辑瞬间',
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
          ),
          actions: [
            Padding(
              padding: const EdgeInsets.only(right: 12),
              child: FilledButton(
                onPressed: draft == null || busy || conflict ? null : _submit,
                child: Text(
                  pending
                      ? '确认发布'
                      : widget.id == null
                      ? '发布'
                      : '保存',
                ),
              ),
            ),
          ],
        ),
        body: state == null || draft == null
            ? async.isLoading
                  ? const LoadingState()
                  : PageState(
                      title: '无法打开编辑器',
                      message: errorMessage(async.error),
                      onRetry: () => ref.invalidate(provider),
                    )
            : SafeArea(
                child: ListView(
                  controller: _editorScroll,
                  keyboardDismissBehavior:
                      ScrollViewKeyboardDismissBehavior.onDrag,
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
                  children: [
                    if (busy) ...[
                      const LinearProgressIndicator(),
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                        child: Text(state.progress!),
                      ),
                    ],
                    if (pending)
                      const Padding(
                        padding: EdgeInsets.symmetric(vertical: 8),
                        child: Text('上次发布结果待确认。点「确认发布」会先查询原记录。'),
                      ),
                    if (state.error != null)
                      Container(
                        padding: const EdgeInsets.all(12),
                        margin: const EdgeInsets.only(bottom: 12),
                        decoration: BoxDecoration(
                          color: colors.errorContainer,
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              errorMessage(state.error),
                              style: TextStyle(color: colors.onErrorContainer),
                            ),
                            if (conflict)
                              TextButton(
                                onPressed: busy
                                    ? null
                                    : () async {
                                        final confirm = await showDialog<bool>(
                                          context: context,
                                          builder: (context) => AlertDialog(
                                            title: const Text('用草稿覆盖新版本？'),
                                            content: const Text(
                                              '可以先保存草稿并返回详情核对最新内容。继续后将保留当前草稿，并用新的版本号提交。',
                                            ),
                                            actions: [
                                              TextButton(
                                                onPressed: () =>
                                                    context.pop(false),
                                                child: const Text('取消'),
                                              ),
                                              FilledButton(
                                                onPressed: () =>
                                                    context.pop(true),
                                                child: const Text('保留草稿内容'),
                                              ),
                                            ],
                                          ),
                                        );
                                        if (mounted && confirm == true) {
                                          await _editor.useLatestVersion();
                                        }
                                      },
                                child: const Text('获取新版本'),
                              ),
                          ],
                        ),
                      ),
                    MomentImageStrip(
                      images: draft.images,
                      onAdd: editable
                          ? () => _pickImages(20 - draft.images.length)
                          : null,
                      onRemove: editable
                          ? (index) => _editor.updateDraft((draft) {
                              final images = [...draft.images]..removeAt(index);
                              return draft.withImages(
                                List.unmodifiable(images),
                              );
                            })
                          : null,
                    ),
                    const SizedBox(height: 12),
                    MomentEditorField(
                      initial: draft.title,
                      label: '标题',
                      hint: '给这个瞬间起个名字',
                      enabled: editable,
                      maxLength: 255,
                      style: const TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w600,
                      ),
                      onChanged: (value) => _editor.updateDraft(
                        (draft) => draft.copyWith(title: value),
                      ),
                    ),
                    MomentEditorField(
                      initial: draft.body,
                      label: '记录此刻',
                      hint: '今天，有什么想记住的？',
                      enabled: editable,
                      minLines: 5,
                      onChanged: (value) => _editor.updateDraft(
                        (draft) => draft.copyWith(body: value),
                      ),
                    ),
                    MomentEditorRow(
                      title: '标签',
                      value: draft.tags.isEmpty
                          ? '添加标签'
                          : draft.tags.map((tag) => '#$tag').join('  '),
                      icon: Icons.tag,
                      onTap: !editable
                          ? null
                          : () async {
                              final tags = await showMomentTags(
                                context,
                                draft.tags,
                              );
                              if (mounted && tags != null) {
                                _editor.updateDraft(
                                  (draft) => draft.copyWith(tags: tags),
                                );
                              }
                            },
                    ),
                    MomentEditorRow(
                      title: '此刻心情',
                      value: draft.mood ?? '选择心情',
                      icon: Icons.sentiment_satisfied_alt,
                      onTap: !editable
                          ? null
                          : () async {
                              final result = await showMomentMood(
                                context,
                                draft.mood,
                              );
                              if (mounted && result != null) {
                                _editor.updateDraft(
                                  (draft) => draft.copyWith(
                                    mood: () => result.isEmpty ? null : result,
                                  ),
                                );
                              }
                            },
                    ),
                    MomentEditorRow(
                      title: '记忆时间',
                      value: draft.time == null
                          ? '今天'
                          : momentDate(draft.time!),
                      icon: Icons.calendar_today_outlined,
                      onTap: !editable ? null : () => _pickDate(draft),
                    ),
                    MomentEditorField(
                      initial: draft.location?.name ?? '',
                      label: '地点',
                      hint: '你在哪里',
                      enabled: editable,
                      maxLength: 255,
                      onChanged: (value) => _editor.updateDraft((draft) {
                        final old = draft.location;
                        final text = value.trim();
                        return draft.copyWith(
                          location: () => text.isEmpty
                              ? null
                              : (
                                  name: text,
                                  address: old?.address,
                                  latitude: old?.latitude,
                                  longitude: old?.longitude,
                                ),
                        );
                      }),
                    ),
                    if (widget.id == null)
                      GatheringSelectionField(
                        selectedId: draft.gatheringId,
                        onTap: editable ? () => _pickGathering(draft) : null,
                      ),
                    MomentEditorRow(
                      title: '可见范围',
                      value: widget.id != null
                          ? '分享设置通过详情入口管理'
                          : draft.circleIds.isEmpty
                          ? (draft.gatheringId == null
                                ? '私人 · 仅自己可见'
                                : '关联同行成员可见')
                          : draft.circleIds
                                .map((id) => _sourceLabel(state, id, id))
                                .join('、'),
                      icon: Icons.lock_outline,
                      onTap: !editable ? null : () => _pickCircles(state),
                    ),
                    const Padding(
                      padding: EdgeInsets.only(top: 16),
                      child: Text(
                        '你的记录，由你决定谁能看见。',
                        style: TextStyle(color: Tokens.muted, fontSize: 12),
                      ),
                    ),
                  ],
                ),
              ),
      ),
    );
  }

  String _sourceLabel(MomentEditorState state, String? id, String fallback) {
    if (id == null) return fallback;
    return _knownSources(
          state,
        ).where((source) => source.id == id).firstOrNull?.name ??
        id;
  }
}
