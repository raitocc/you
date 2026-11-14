import 'package:flutter/material.dart';

import '../../../../core/network/api_client.dart';

/// 创建和编辑共用的表单，集中管理输入、长度校验和提交中的交互状态。
class GatheringInfoForm extends StatefulWidget {
  const GatheringInfoForm({
    super.key,
    required this.title,
    required this.heading,
    required this.description,
    required this.submitLabel,
    required this.busy,
    required this.onSubmit,
    this.initialName = '',
    this.initialDescription = '',
    this.error,
  });

  final String title;
  final String heading;
  final String description;
  final String submitLabel;
  final String initialName;
  final String initialDescription;
  final bool busy;
  final Object? error;
  final Future<void> Function(String name, String description) onSubmit;

  @override
  State<GatheringInfoForm> createState() => _GatheringInfoFormState();
}

class _GatheringInfoFormState extends State<GatheringInfoForm> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _description = TextEditingController();

  @override
  void initState() {
    super.initState();
    _name.text = widget.initialName;
    _description.text = widget.initialDescription;
  }

  @override
  void dispose() {
    _name.dispose();
    _description.dispose();
    super.dispose();
  }

  /// 校验输入后委托页面提交，表单负责保留输入。
  Future<void> _submit() async {
    if (!_form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    await widget.onSubmit(_name.text, _description.text);
  }

  /// 名称按 Unicode 码点计数，与后端长度约定一致。
  String? _validateName(String? input) {
    final name = (input ?? '').trim();
    if (name.isEmpty) return '请填写同行名称';
    if (name.runes.length > 128) return '同行名称最多 128 个字符';
    return null;
  }

  String? _validateDescription(String? input) {
    if ((input ?? '').runes.length > 5000) return '同行介绍最多 5000 个字符';
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final busy = widget.busy;
    return PopScope(
      canPop: !busy,
      child: Scaffold(
        appBar: AppBar(title: Text(widget.title)),
        bottomNavigationBar: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: FilledButton(
              onPressed: busy ? null : _submit,
              child: Text(busy ? '正在保存…' : widget.submitLabel),
            ),
          ),
        ),
        body: Form(
          key: _form,
          child: ListView(
            padding: const EdgeInsets.all(18),
            children: [
              Text(
                widget.heading,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 8),
              Text(widget.description),
              const SizedBox(height: 28),
              TextFormField(
                controller: _name,
                enabled: !busy,
                validator: _validateName,
                textInputAction: TextInputAction.next,
                decoration: const InputDecoration(
                  labelText: '同行名称',
                  hintText: '例如：毕业旅行',
                ),
              ),
              const SizedBox(height: 18),
              TextFormField(
                controller: _description,
                enabled: !busy,
                validator: _validateDescription,
                minLines: 4,
                maxLines: 8,
                decoration: const InputDecoration(labelText: '同行介绍（选填）'),
              ),
              if (widget.error != null) ...[
                const SizedBox(height: 18),
                Text(
                  errorMessage(widget.error),
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
