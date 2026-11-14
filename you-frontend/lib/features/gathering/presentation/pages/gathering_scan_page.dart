import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../../../../core/logging/app_logger.dart';
import '../../../../shared/ui/page_state.dart';

/// 扫码只负责读取邀请码，相机启停与资源释放由 MobileScanner 管理。
class GatheringScanPage extends StatefulWidget {
  const GatheringScanPage({super.key});

  @override
  State<GatheringScanPage> createState() => _GatheringScanPageState();
}

class _GatheringScanPageState extends State<GatheringScanPage> {
  bool _returned = false;
  String _hint = '将朋友展示的同行二维码放入画面';

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('扫描同行二维码')),
    body: Column(
      children: [
        Expanded(
          child: MobileScanner(
            onDetect: _onDetect,
            onDetectError: _onScanError,
            errorBuilder: (context, error) => const PageState(
              title: '相机暂时无法使用',
              message: '请在系统设置中允许相机权限，也可以返回输入邀请码。',
            ),
          ),
        ),
        SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              children: [
                Text(_hint, textAlign: TextAlign.center),
                TextButton(
                  onPressed: () => context.pop(),
                  child: const Text('返回输入邀请码'),
                ),
              ],
            ),
          ),
        ),
      ],
    ),
  );

  /// 同一帧可能识别多个码；返回首个有效内容后锁定，避免重复导航。
  void _onDetect(BarcodeCapture capture) {
    if (_returned || !mounted) return;
    for (final barcode in capture.barcodes) {
      if (barcode.format != BarcodeFormat.qrCode) continue;
      final code = barcode.rawValue?.trim();
      if (code == null || code.isEmpty || code.runes.length > 16) continue;
      _returned = true;
      context.pop(code);
      return;
    }
    setState(() => _hint = '请扫描 YOU 同行二维码，或返回输入邀请码');
  }

  void _onScanError(Object error, StackTrace stackTrace) {
    AppLogger.error(
      AppLogEvent.gatheringScanFailed,
      error: error,
      stackTrace: stackTrace,
    );
    if (mounted) setState(() => _hint = '识别失败，请重新对准二维码或返回输入邀请码');
  }
}
