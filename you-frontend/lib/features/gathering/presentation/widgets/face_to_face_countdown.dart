import 'dart:async';
import 'package:flutter/material.dart';

/// 按服务端截止时间重算；应用从后台恢复后也沿用绝对时间。
class FaceToFaceCountdown extends StatefulWidget {
  const FaceToFaceCountdown({
    super.key,
    required this.expiresAt,
    required this.builder,
  });
  final DateTime expiresAt;
  final Widget Function(BuildContext, Duration) builder;
  @override
  State<FaceToFaceCountdown> createState() => _FaceToFaceCountdownState();

  static String label(Duration remaining) {
    if (remaining <= Duration.zero) return '短码已过期';
    final seconds = remaining.inSeconds;
    return '剩余 ${seconds ~/ 60}:${(seconds % 60).toString().padLeft(2, '0')}';
  }
}

class _FaceToFaceCountdownState extends State<FaceToFaceCountdown> {
  late final Timer _timer;
  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _timer.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) =>
      widget.builder(context, widget.expiresAt.difference(DateTime.now()));
}
