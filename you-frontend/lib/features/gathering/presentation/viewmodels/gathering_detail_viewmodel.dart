import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../shared/models/content_revision.dart';
import '../../../../core/auth/session.dart';
import '../../data/gathering_repository.dart';
import '../../models/gathering.dart';

/// 按同行 ID 独立加载，切换账号时重新读取。
final gatheringDetailProvider = FutureProvider.autoDispose
    .family<Gathering, String>((ref, id) {
      ref.watch(sessionProvider);
      ref.watch(momentRevisionProvider);
      return ref.watch(gatheringRepositoryProvider).detail(id);
    });
