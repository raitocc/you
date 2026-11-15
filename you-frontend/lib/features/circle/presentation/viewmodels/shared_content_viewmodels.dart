import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/auth/session.dart';
import '../../../../shared/models/content_revision.dart';
import '../../../album/models/album.dart';
import '../../../moment/models/moment.dart';
import '../../data/circle_repository.dart';
import 'circle_viewmodels.dart';

/// momentId 为空表示直分享瞬间，有值表示共享时光内的瞬间。
/// 完整来源组成缓存键，同一内容在不同圈子分别请求授权。
typedef SharedMomentKey = ({String circleId, String shareId, String? momentId});

final circleSharedMomentProvider = FutureProvider.autoDispose
    .family<Moment, SharedMomentKey>((ref, key) {
      ref.watch(sessionProvider);
      ref.watch(momentRevisionProvider);
      final repository = ref.watch(circleRepositoryProvider);
      final momentId = key.momentId;
      if (momentId == null) {
        return repository.sharedMoment(key.circleId, key.shareId);
      }
      return repository.sharedAlbumMoment(key.circleId, key.shareId, momentId);
    }, retry: (_, _) => null);

final circleSharedAlbumProvider = FutureProvider.autoDispose
    .family<Album, SharedTimelineKey>((ref, key) {
      ref.watch(sessionProvider);
      ref.watch(albumRevisionProvider);
      return ref
          .watch(circleRepositoryProvider)
          .sharedAlbum(key.circleId, key.shareId);
    }, retry: (_, _) => null);
