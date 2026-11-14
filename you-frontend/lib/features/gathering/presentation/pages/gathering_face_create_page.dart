import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../viewmodels/gathering_face_create_viewmodel.dart';
import '../widgets/gathering_info_form.dart';
import '../widgets/face_code_field.dart';
import '../widgets/face_to_face_error.dart';

/// 两步表单：先填写名称与介绍，再确认短码；提交成功直接进入同行详情。
class GatheringFaceCreatePage extends ConsumerStatefulWidget {
  const GatheringFaceCreatePage({super.key});
  @override
  ConsumerState<GatheringFaceCreatePage> createState() =>
      _GatheringFaceCreatePageState();
}

class _GatheringFaceCreatePageState
    extends ConsumerState<GatheringFaceCreatePage> {
  final _form = GlobalKey<FormState>();
  final _code = TextEditingController();
  String _name = '';
  String _description = '';
  bool _enteringCode = false;
  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(gatheringFaceCreateProvider);
    if (!_enteringCode) {
      return GatheringInfoForm(
        title: '发起同行',
        heading: '为这次经历起个名字',
        description: '先填写同行信息，下一步约定四位数字短码。',
        submitLabel: '下一步',
        busy: false,
        initialName: _name,
        initialDescription: _description,
        onSubmit: (name, description) async => setState(() {
          _name = name;
          _description = description;
          _enteringCode = true;
        }),
      );
    }
    return PopScope(
      canPop: !state.busy && state.created != null,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && !state.busy) setState(() => _enteringCode = false);
      },
      child: Scaffold(
        appBar: AppBar(
          title: const Text('设置面对面短码'),
          leading: BackButton(
            onPressed: state.busy
                ? null
                : () {
                    if (state.created != null) {
                      context.pop();
                    } else {
                      setState(() => _enteringCode = false);
                    }
                  },
          ),
        ),
        body: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            Text(
              _name.trim(),
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 16),
            const Text('输入约定的四位数字，确认后获取定位并发起同行。'),
            const SizedBox(height: 16),
            Form(
              key: _form,
              child: FaceCodeField(controller: _code, enabled: !state.busy),
            ),
            const SizedBox(height: 20),
            FilledButton(
              onPressed: state.busy ? null : _submit,
              child: Text(state.busy ? '正在发起…' : '确认发起'),
            ),
            if (state.busy) const LinearProgressIndicator(),
            if (state.error != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: FaceToFaceError(error: state.error!),
              ),
            if (state.created != null && state.error != null) ...[
              const Text('同行已创建，重试将继续开启短码。'),
              TextButton(
                onPressed: state.busy ? null : () => _enter(state.created!.id),
                child: const Text('进入已创建的同行'),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _submit() async {
    if (!_form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    final gathering = await ref
        .read(gatheringFaceCreateProvider.notifier)
        .create(_name, _description, _code.text);
    if (mounted && gathering != null) _enter(gathering.id);
  }

  /// 发起成功直接展示详情，沿用项目的返回导航。
  void _enter(String id) => context.replace('/gatherings/$id');
}
