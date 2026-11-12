import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import '../../../../core/network/api_client.dart';
import '../../../../core/errors/app_exception.dart';
import '../../../../shared/models/uuid_v7.dart';
import '../../models/circle.dart';
import '../viewmodels/circle_viewmodels.dart';
import '../widgets/circle_widgets.dart';

Future<void> showCircleEditor(
  BuildContext context, {
  required CircleDetails details,
}) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  builder: (_) => _CircleForm(details: details),
);

class CircleNewPage extends StatelessWidget {
  const CircleNewPage({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('新的圈子')),
    body: const _CircleForm(),
  );
}

class _CircleForm extends ConsumerStatefulWidget {
  const _CircleForm({this.details});
  final CircleDetails? details;
  @override
  ConsumerState<_CircleForm> createState() => _CircleFormState();
}

class _CircleFormState extends ConsumerState<_CircleForm> {
  final form = GlobalKey<FormState>();
  late final String id = widget.details?.circle.id ?? uuidV7();
  late final name = TextEditingController(text: widget.details?.circle.name);
  late final description = TextEditingController(
    text: widget.details?.description,
  );
  late String? avatarId = widget.details?.avatarFileId;
  Uint8List? avatarBytes;
  bool busy = false;
  bool avatarCleared = false;
  Object? error;

  @override
  void dispose() {
    name.dispose();
    description.dispose();
    super.dispose();
  }

  Future<void> run(Future<void> Function() work) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await work();
    } catch (e) {
      if (mounted) setState(() => error = e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> pick() => run(() async {
    final image = await ImagePicker().pickImage(source: ImageSource.gallery);
    if (image == null || !mounted) return;
    if (await image.length() > 10 * 1024 * 1024) {
      throw const AppException('INVALID_IMAGE', '请选择 10 MiB 以内的图片');
    }
    final bytes = await image.readAsBytes();
    final mime = switch (image.name.split('.').last.toLowerCase()) {
      'jpg' || 'jpeg' => 'image/jpeg',
      'png' => 'image/png',
      'webp' => 'image/webp',
      _ => image.mimeType ?? '',
    };
    if (!mounted) return;
    final uploaded = await ref
        .read(circleActionsProvider)
        .uploadAvatar(bytes, mime);
    if (mounted) {
      setState(() {
        avatarId = uploaded;
        avatarBytes = bytes;
      });
    }
  });

  Future<void> save() => run(() async {
    if (!form.currentState!.validate()) return;
    final creating = widget.details == null;
    final intro = description.text.trim();
    await ref
        .read(circleActionsProvider)
        .save(
          id,
          creating,
          name.text.trim(),
          intro.isEmpty ? null : intro,
          avatarId,
        );
    if (!mounted) return;
    if (creating) {
      context.go('/circles?selected=$id');
    } else {
      Navigator.pop(context);
    }
  });

  @override
  Widget build(BuildContext context) => SingleChildScrollView(
    padding: EdgeInsets.fromLTRB(
      20,
      20,
      20,
      24 +
          (widget.details == null
              ? 0
              : MediaQuery.viewInsetsOf(context).bottom),
    ),
    child: Form(
      key: form,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (widget.details != null)
            Text('编辑圈子', style: Theme.of(context).textTheme.titleLarge),
          Center(
            child: avatarBytes == null
                ? CircleProfileAvatar(
                    avatarCleared ? null : widget.details?.circle.avatarUrl,
                    radius: 36,
                  )
                : ClipOval(
                    child: Image.memory(
                      avatarBytes!,
                      width: 72,
                      height: 72,
                      fit: BoxFit.cover,
                    ),
                  ),
          ),
          Wrap(
            alignment: WrapAlignment.center,
            children: [
              TextButton(
                onPressed: busy ? null : pick,
                child: const Text('选择头像'),
              ),
              if (avatarId != null)
                TextButton(
                  onPressed: busy
                      ? null
                      : () => setState(() {
                          avatarId = null;
                          avatarBytes = null;
                          avatarCleared = true;
                        }),
                  child: const Text('移除头像'),
                ),
            ],
          ),
          TextFormField(
            controller: name,
            enabled: !busy,
            decoration: const InputDecoration(labelText: '圈子名称'),
            validator: (text) {
              final length = text?.trim().runes.length ?? 0;
              return length == 0 || length > 128 ? '请输入 1–128 个字符' : null;
            },
          ),
          const SizedBox(height: 12),
          TextFormField(
            controller: description,
            enabled: !busy,
            maxLines: 3,
            decoration: const InputDecoration(labelText: '圈子介绍'),
            validator: (text) => (text?.trim().runes.length ?? 0) > 5000
                ? '介绍最多 5000 个字符'
                : null,
          ),
          const SizedBox(height: 20),
          if (error != null) Text(errorMessage(error)),
          if (busy) const LinearProgressIndicator(),
          FilledButton(
            onPressed: busy ? null : save,
            child: Text(widget.details == null ? '创建圈子' : '保存'),
          ),
          if (widget.details == null)
            OutlinedButton(
              onPressed: busy ? null : () => context.push('/circles/join'),
              child: const Text('凭邀请码加入'),
            ),
        ],
      ),
    ),
  );
}

class CircleJoinPage extends ConsumerStatefulWidget {
  const CircleJoinPage({super.key});
  @override
  ConsumerState<CircleJoinPage> createState() => _CircleJoinPageState();
}

class _CircleJoinPageState extends ConsumerState<CircleJoinPage> {
  final code = TextEditingController();
  CirclePreview? preview;
  bool busy = false;
  String? error;

  @override
  void dispose() {
    code.dispose();
    super.dispose();
  }

  Future<void> submit() async {
    if (busy) return;
    final number = int.tryParse(code.text);
    if (number == null || number < 10000) {
      setState(() => error = '请输入有效的数字邀请码');
      return;
    }
    final current = preview;
    final actions = ref.read(circleActionsProvider);
    setState(() {
      busy = true;
      error = null;
    });
    try {
      if (current == null) {
        final result = await actions.preview(number);
        if (mounted) setState(() => preview = result);
      } else {
        if (!current.isMember) await actions.join(number);
        if (!mounted) return;
        if (current.isMember) {
          context.go('/circles?selected=${current.circle.id}');
          context.push('/circles/${current.circle.id}');
        } else {
          context.go('/circles?selected=${current.circle.id}');
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          error = errorMessage(e);
          preview = null;
        });
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('凭邀请码加入')),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        TextField(
          controller: code,
          enabled: !busy,
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          decoration: InputDecoration(labelText: '邀请码', errorText: error),
          onChanged: (_) => setState(() {
            preview = null;
            error = null;
          }),
        ),
        if (preview case final result?)
          ListTile(
            leading: CircleProfileAvatar(result.circle.avatarUrl),
            title: Text(result.circle.name),
            subtitle: Text(
              ['创建者 · ${result.creator.name}', ?result.description].join('\n'),
            ),
          ),
        const SizedBox(height: 16),
        if (busy) const LinearProgressIndicator(),
        FilledButton(
          onPressed: busy ? null : submit,
          child: Text(
            preview == null
                ? '查看圈子'
                : preview!.isMember
                ? '打开圈子'
                : '确认加入',
          ),
        ),
      ],
    ),
  );
}
