import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 跨页面变更只在当前会话内生效。
final momentRevisionProvider = NotifierProvider<MomentRevision, int>(
  MomentRevision.new,
);

class MomentRevision extends Notifier<int> {
  @override
  int build() => 0;

  /// 通知当前会话内的页面刷新。
  void changed() => state++;
}

final albumRevisionProvider = NotifierProvider<MomentRevision, int>(
  MomentRevision.new,
);
