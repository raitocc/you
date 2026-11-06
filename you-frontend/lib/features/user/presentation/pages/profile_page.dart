import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import '../../../../core/errors/app_exception.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../viewmodels/profile_viewmodel.dart';

class ProfilePage extends ConsumerStatefulWidget {
  const ProfilePage({super.key});

  @override
  ConsumerState<ProfilePage> createState() => _ProfilePageState();
}

enum _Field { nickname, signature }

class _ProfilePageState extends ConsumerState<ProfilePage> {
  static const _forest = AssetImage('assets/profile/forest.jpg');
  static const _green = Color(0xff26332d);
  final _text = TextEditingController();
  bool _editing = false;
  _Field? _field;
  String? _status;
  String? _error;

  bool get _busy => _status != null;
  ProfileViewModel get _model => ref.read(profileProvider.notifier);

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _run(String status, Future<void> Function() work) async {
    if (_busy) return;
    setState(() {
      _status = status;
      _error = null;
    });
    try {
      await work();
    } catch (error) {
      if (mounted) setState(() => _error = errorMessage(error));
    } finally {
      if (mounted) setState(() => _status = null);
    }
  }

  Future<void> _pick({required bool avatar}) =>
      _run(avatar ? '正在上传头像…' : '正在保存背景…', () async {
        final file = await ImagePicker().pickImage(source: ImageSource.gallery);
        if (file == null || !mounted) return;
        const invalid = AppException(
          'INVALID_IMAGE',
          '请选择 10 MiB 以内的 JPEG、PNG 或 WebP 图片',
        );
        if (await file.length() > 10485760) throw invalid;
        final bytes = await file.readAsBytes();
        final mime = _mime(bytes);
        if (mime == null) throw invalid;
        try {
          final codec = await ui.instantiateImageCodec(bytes, targetWidth: 96);
          try {
            (await codec.getNextFrame()).image.dispose();
          } finally {
            codec.dispose();
          }
        } catch (_) {
          throw invalid;
        }
        if (!mounted) return;
        if (avatar) {
          await _model.save(avatar: (bytes: bytes, mimeType: mime));
        } else {
          await _model.save(background: bytes);
        }
      });

  String? _mime(Uint8List bytes) {
    if (bytes.length < 12 || bytes.length > 10485760) return null;
    if (bytes[0] == 0xff && bytes[1] == 0xd8 && bytes[2] == 0xff) {
      return 'image/jpeg';
    }
    if (bytes[0] == 0x89 &&
        String.fromCharCodes(bytes.sublist(1, 4)) == 'PNG') {
      return 'image/png';
    }
    if (String.fromCharCodes(bytes.sublist(0, 4)) == 'RIFF' &&
        String.fromCharCodes(bytes.sublist(8, 12)) == 'WEBP') {
      return 'image/webp';
    }
    return null;
  }

  void _back() {
    if (_busy) return;
    if (!_editing) {
      if (context.canPop()) {
        context.pop();
      } else {
        context.go('/moments');
      }
      return;
    }
    setState(() {
      _error = null;
      if (_field != null) {
        _field = null;
      } else {
        _editing = false;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final profile = ref.watch(profileProvider);
    return PopScope(
      canPop: !_busy && !_editing,
      onPopInvokedWithResult: (popped, _) {
        if (!popped && !_busy && _editing) _back();
      },
      child: Align(
        alignment: Alignment.bottomCenter,
        child: Material(
          color: _editing ? const Color(0xfff3f4f2) : _green,
          borderRadius: _editing
              ? null
              : const BorderRadius.vertical(top: Radius.circular(24)),
          clipBehavior: Clip.antiAlias,
          child: SizedBox(
            width: double.infinity,
            height: _editing ? double.infinity : null,
            child: profile.when(
              data: (data) => _editing ? _editor(data) : _card(data),
              loading: () => const SizedBox(height: 220, child: LoadingState()),
              error: (error, _) => ColoredBox(
                color: const Color(0xfff3f4f2),
                child: SizedBox(
                  height: 260,
                  child: PageState(
                    title: '资料加载失败',
                    message: errorMessage(error),
                    onRetry: () => ref.invalidate(profileProvider),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  ImageProvider _cover(ProfileCardData data) => data.edits.background == null
      ? _forest
      : MemoryImage(data.edits.background!);

  String _name(ProfileCardData data) {
    final name = data.edits.nickname ?? data.user.nickname;
    return name.trim().isEmpty ? '未设置昵称' : name;
  }

  Widget _grip(Color color) => GestureDetector(
    behavior: HitTestBehavior.opaque,
    onVerticalDragEnd: (details) {
      if ((details.primaryVelocity ?? 0) > 0) _back();
    },
    child: SizedBox(
      width: double.infinity,
      height: 30,
      child: Center(
        child: Container(
          width: 34,
          height: 4,
          decoration: BoxDecoration(
            color: color,
            borderRadius: BorderRadius.circular(4),
          ),
        ),
      ),
    ),
  );

  Widget _avatar() {
    final avatar = ref.watch(profileAvatarProvider).asData?.value;
    return Semantics(
      button: true,
      label: '更换头像',
      child: InkWell(
        onTap: _busy ? null : () => _pick(avatar: true),
        customBorder: const CircleBorder(),
        child: SizedBox.square(
          dimension: 76,
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              Positioned.fill(
                child: ClipOval(
                  child: ColoredBox(
                    color: const Color(0xff364942),
                    child: avatar == null
                        ? const Icon(
                            Icons.person_outline,
                            color: Colors.white,
                            size: 34,
                          )
                        : Image.memory(
                            avatar,
                            fit: BoxFit.cover,
                            errorBuilder: (_, _, _) => const Icon(
                              Icons.broken_image_outlined,
                              color: Colors.white,
                            ),
                          ),
                  ),
                ),
              ),
              const Positioned(
                bottom: -2,
                right: -2,
                child: CircleAvatar(
                  radius: 13,
                  backgroundColor: _green,
                  child: Icon(
                    Icons.image_outlined,
                    color: Colors.white,
                    size: 16,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _feedback(Color color) => Semantics(
    liveRegion: true,
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (_status case final status?) ...[
          const SizedBox(height: 12),
          const LinearProgressIndicator(),
          const SizedBox(height: 8),
          Text(status, style: TextStyle(color: color)),
        ],
        if (_error case final error?)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(error, style: TextStyle(color: color)),
          ),
      ],
    ),
  );

  Widget _card(ProfileCardData data) => Stack(
    children: [
      Positioned.fill(
        child: Image(
          image: _cover(data),
          fit: BoxFit.cover,
          errorBuilder: (_, _, _) => const SizedBox.shrink(),
        ),
      ),
      const Positioned.fill(
        child: DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [Color(0x99142019), Color(0xdb142019)],
            ),
          ),
        ),
      ),
      SafeArea(
        top: false,
        child: SingleChildScrollView(
          child: DefaultTextStyle(
            style: const TextStyle(color: Colors.white, fontSize: 14),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                _grip(const Color(0xa6ffffff)),
                Padding(
                  padding: const EdgeInsets.fromLTRB(22, 0, 22, 28),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          _avatar(),
                          const SizedBox(width: 16),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  _name(data),
                                  style: const TextStyle(
                                    fontSize: 25,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                                const SizedBox(height: 5),
                                Text('you ID：${data.user.username}'),
                              ],
                            ),
                          ),
                          _tool(
                            Icons.edit_outlined,
                            '编辑资料',
                            () => setState(() {
                              _editing = true;
                              _error = null;
                            }),
                          ),
                          _tool(
                            Icons.image_outlined,
                            '更换背景',
                            () => _pick(avatar: false),
                          ),
                        ],
                      ),
                      const SizedBox(height: 22),
                      Wrap(
                        spacing: 22,
                        runSpacing: 8,
                        children: [
                          for (final label in ['瞬间', '同行', '圈子'])
                            Text.rich(
                              TextSpan(
                                children: [
                                  const TextSpan(
                                    text: '0 ',
                                    style: TextStyle(
                                      fontSize: 19,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                  TextSpan(text: label),
                                ],
                              ),
                            ),
                        ],
                      ),
                      const SizedBox(height: 14),
                      Text(
                        data.edits.signature.isEmpty
                            ? '还没有个性签名'
                            : data.edits.signature,
                      ),
                      _feedback(const Color(0xffffe4df)),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ],
  );

  Widget _tool(IconData icon, String label, VoidCallback onPressed) =>
      IconButton(
        tooltip: label,
        onPressed: _busy ? null : onPressed,
        color: Colors.white,
        iconSize: 17,
        icon: CircleAvatar(
          radius: 15,
          backgroundColor: const Color(0xb3142019),
          foregroundColor: Colors.white,
          child: Icon(icon, size: 17),
        ),
      );

  void _edit(_Field field, String value) {
    _text.text = value;
    setState(() {
      _error = null;
      _field = field;
    });
  }

  Future<void> _saveField() async {
    final nickname = _field == _Field.nickname;
    final value = _text.text.trim();
    if (nickname && value.isEmpty) {
      setState(() => _error = '请输入昵称');
      return;
    }
    await _run('正在保存…', () async {
      await _model.save(
        nickname: nickname ? value : null,
        signature: nickname ? null : value,
      );
      if (mounted) setState(() => _field = null);
    });
  }

  Widget _editor(ProfileCardData data) {
    final field = _field;
    final label = field == _Field.nickname ? '昵称' : '个性签名';
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(
          bottom: MediaQuery.viewInsetsOf(context).bottom,
        ),
        child: ListView(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          children: [
            _grip(const Color(0xffb9bdb8)),
            Row(
              children: [
                IconButton(
                  tooltip: '返回',
                  onPressed: _busy ? null : _back,
                  icon: const Icon(Icons.arrow_back_ios_new, size: 18),
                ),
                Expanded(
                  child: Text(
                    field == null ? '编辑资料' : '编辑$label',
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      fontWeight: FontWeight.bold,
                      fontSize: 18,
                    ),
                  ),
                ),
                TextButton(
                  onPressed: _busy
                      ? null
                      : (field == null ? _back : _saveField),
                  child: Text(field == null ? '预览' : '保存'),
                ),
              ],
            ),
            if (field == null) ...[
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 24),
                child: Center(child: _avatar()),
              ),
              _group([
                _row(
                  '昵称',
                  Text(_name(data)),
                  () => _edit(
                    _Field.nickname,
                    data.edits.nickname ?? data.user.nickname,
                  ),
                ),
                _row('you ID', Text(data.user.username)),
                _row(
                  '背景',
                  Align(
                    alignment: Alignment.centerLeft,
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(6),
                      child: Image(
                        image: _cover(data),
                        width: 48,
                        height: 38,
                        fit: BoxFit.cover,
                        errorBuilder: (_, _, _) =>
                            const Icon(Icons.broken_image_outlined),
                      ),
                    ),
                  ),
                  () => _pick(avatar: false),
                ),
              ]),
              const SizedBox(height: 12),
              _group([
                _row(
                  '个性签名',
                  Text(
                    data.edits.signature.isEmpty
                        ? '添加签名'
                        : data.edits.signature,
                  ),
                  () => _edit(_Field.signature, data.edits.signature),
                ),
              ]),
              const SizedBox(height: 16),
              TextButton(
                onPressed: _busy ? null : () => _run('正在退出…', _model.logout),
                child: const Text('退出登录'),
              ),
            ] else ...[
              const SizedBox(height: 20),
              _group([
                Padding(
                  padding: const EdgeInsets.all(14),
                  child: TextField(
                    key: ValueKey(field),
                    controller: _text,
                    enabled: !_busy,
                    maxLength: field == _Field.nickname ? 24 : 160,
                    maxLines: field == _Field.nickname ? 1 : 5,
                    decoration: InputDecoration(
                      labelText: label,
                      border: InputBorder.none,
                    ),
                  ),
                ),
              ]),
            ],
            _feedback(const Color(0xff982d28)),
          ],
        ),
      ),
    );
  }

  Widget _group(List<Widget> rows) => Material(
    color: Colors.white,
    borderRadius: BorderRadius.circular(16),
    clipBehavior: Clip.antiAlias,
    child: Column(
      children: [
        for (var i = 0; i < rows.length; i++) ...[
          if (i != 0) const Divider(height: 1),
          rows[i],
        ],
      ],
    ),
  );

  Widget _row(String label, Widget value, [VoidCallback? action]) => InkWell(
    onTap: _busy ? null : action,
    child: ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 52),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        child: Row(
          children: [
            SizedBox(
              width: 80,
              child: Text(
                label,
                style: const TextStyle(color: Color(0xff515852)),
              ),
            ),
            Expanded(child: value),
            if (action != null) const Icon(Icons.chevron_right, size: 18),
          ],
        ),
      ),
    ),
  );
}
