import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../models/gathering.dart';
import '../viewmodels/gathering_detail_viewmodel.dart';

/// 二维码内容即邀请码，扫描后进入与手动输入相同的加入预览。
class GatheringInvitePage extends ConsumerWidget {
  const GatheringInvitePage({super.key, required this.id});
  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = gatheringDetailProvider(id);
    final value = ref.watch(provider);
    return Scaffold(
      appBar: AppBar(title: const Text('邀请同行')),
      body: value.when(
        loading: () => const LoadingState(),
        error: (error, _) => PageState(
          title: '邀请码读取失败',
          message: errorMessage(error),
          onRetry: () => ref.invalidate(provider),
        ),
        data: (gathering) => gathering.status == GatheringStatus.ended
            ? const PageState(title: '同行已结束', message: '邀请已关闭，现有成员可以继续浏览共同记录。')
            : _Invitation(gathering: gathering),
      ),
    );
  }
}

class _Invitation extends StatelessWidget {
  const _Invitation({required this.gathering});
  final Gathering gathering;

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(24),
    children: [
      Text(gathering.name, style: Theme.of(context).textTheme.headlineSmall),
      const SizedBox(height: 12),
      const Text('让朋友在「加入同行」中扫码，或输入下方邀请码。'),
      const SizedBox(height: 24),
      Center(
        child: ColoredBox(
          color: Colors.white,
          child: QrImageView(
            data: gathering.inviteCode,
            size: 240,
            padding: const EdgeInsets.all(20),
          ),
        ),
      ),
      const SizedBox(height: 24),
      SelectableText(
        gathering.inviteCode,
        textAlign: TextAlign.center,
        style: Theme.of(context).textTheme.titleLarge,
      ),
      const SizedBox(height: 16),
      OutlinedButton.icon(
        onPressed: () => _copyCode(context),
        icon: const Icon(Icons.copy_outlined),
        label: const Text('复制邀请码'),
      ),
    ],
  );

  Future<void> _copyCode(BuildContext context) async {
    await Clipboard.setData(ClipboardData(text: gathering.inviteCode));
    if (!context.mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('邀请码已复制')));
  }
}
