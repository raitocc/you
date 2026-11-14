import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/network/api_client.dart';
import '../viewmodels/gathering_join_viewmodel.dart';
import '../widgets/gathering_join_preview_card.dart';

/// 输入和扫描都先核对目标，再确认加入。
class GatheringJoinPage extends ConsumerStatefulWidget {
  const GatheringJoinPage({super.key});

  @override
  ConsumerState<GatheringJoinPage> createState() => _GatheringJoinPageState();
}

class _GatheringJoinPageState extends ConsumerState<GatheringJoinPage> {
  final _form = GlobalKey<FormState>();
  final _code = TextEditingController();

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(gatheringJoinProvider);
    final preview = state.preview;
    return PopScope(
      canPop: !state.busy,
      child: Scaffold(
        appBar: AppBar(title: const Text('加入同行')),
        body: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            Text('和朋友一起记录', style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 12),
            const Text('输入朋友的邀请码，或扫描同行二维码。'),
            const SizedBox(height: 24),
            Form(
              key: _form,
              child: TextFormField(
                controller: _code,
                enabled: !state.busy,
                decoration: const InputDecoration(labelText: '邀请码'),
                autocorrect: false,
                textInputAction: TextInputAction.search,
                onChanged: ref.read(gatheringJoinProvider.notifier).changeCode,
                onFieldSubmitted: (_) => _preview(),
                validator: _validateCode,
              ),
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 12,
              runSpacing: 8,
              children: [
                FilledButton(
                  onPressed: state.busy ? null : _preview,
                  child: Text(state.busy ? '处理中…' : '查看加入预览'),
                ),
                OutlinedButton.icon(
                  onPressed: state.busy ? null : _scan,
                  icon: const Icon(Icons.qr_code_scanner),
                  label: const Text('扫一扫'),
                ),
              ],
            ),
            if (state.error != null) ...[
              const SizedBox(height: 20),
              Text(
                errorMessage(state.error),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            if (preview != null) ...[
              const SizedBox(height: 24),
              GatheringJoinPreviewCard(
                preview: preview,
                busy: state.busy,
                onConfirm: _confirm,
              ),
            ],
          ],
        ),
      ),
    );
  }

  String? _validateCode(String? value) {
    final code = value?.trim() ?? '';
    if (code.isEmpty) return '请输入邀请码';
    if (code.runes.length > 16) return '邀请码最多 16 个字符';
    return null;
  }

  void _preview() {
    if (!_form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    ref.read(gatheringJoinProvider.notifier).preview(_code.text);
  }

  /// 扫描页只返回邀请码，沿用表单校验和加入预览。
  Future<void> _scan() async {
    FocusScope.of(context).unfocus();
    final code = await context.push<String>('/gatherings/scan');
    if (!mounted || code == null) return;
    _code.text = code;
    ref.read(gatheringJoinProvider.notifier).changeCode(code);
    _preview();
  }

  Future<void> _confirm() async {
    final preview = ref.read(gatheringJoinProvider).preview;
    if (preview == null) return;
    if (preview.isMember) {
      context.replace('/gatherings/${preview.id}');
      return;
    }
    final result = await ref.read(gatheringJoinProvider.notifier).join();
    if (mounted && result != null) context.replace('/gatherings/${result.id}');
  }
}
