import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/auth/session.dart';
import '../../../../shared/models/content_revision.dart';
import '../../../../shared/models/cursor_page.dart';
import '../../../../shared/ui/paged_browse.dart';
import '../../data/circle_repository.dart';
import '../../models/circle.dart';

final circlesProvider =
    AsyncNotifierProvider.autoDispose<
      CirclesViewModel,
      BrowseState<CircleSummary, CircleCursor>
    >(CirclesViewModel.new);

class CirclesViewModel extends PagedBrowse<CircleSummary, CircleCursor> {
  @override
  Future<BrowsePage<CircleSummary, CircleCursor>> fetch(CircleCursor? cursor) =>
      ref.read(circleRepositoryProvider).browse(cursor);
  @override
  String key(CircleSummary item) => item.id;
}

final circleFeedProvider = AsyncNotifierProvider.autoDispose
    .family<
      CircleFeedViewModel,
      BrowseState<CircleShare, CircleCursor>,
      String
    >(CircleFeedViewModel.new);

class CircleFeedViewModel extends PagedBrowse<CircleShare, CircleCursor> {
  CircleFeedViewModel(this.id);
  final String id;
  @override
  Future<BrowseState<CircleShare, CircleCursor>> build() {
    ref.watch(momentRevisionProvider);
    ref.watch(albumRevisionProvider);
    return super.build();
  }

  @override
  Future<BrowsePage<CircleShare, CircleCursor>> fetch(CircleCursor? cursor) =>
      ref.read(circleRepositoryProvider).feed(id, cursor);
  @override
  String key(CircleShare item) => item.id;
}

typedef SharedTimelineKey = ({String circleId, String shareId});
final sharedTimelineProvider = AsyncNotifierProvider.autoDispose
    .family<
      SharedTimelineViewModel,
      BrowseState<CircleMoment, CircleCursor>,
      SharedTimelineKey
    >(SharedTimelineViewModel.new);

class SharedTimelineViewModel extends PagedBrowse<CircleMoment, CircleCursor> {
  SharedTimelineViewModel(this.share);
  final SharedTimelineKey share;
  @override
  Future<BrowseState<CircleMoment, CircleCursor>> build() {
    ref.watch(momentRevisionProvider);
    ref.watch(albumRevisionProvider);
    return super.build();
  }

  @override
  Future<BrowsePage<CircleMoment, CircleCursor>> fetch(CircleCursor? cursor) =>
      ref
          .read(circleRepositoryProvider)
          .timeline(share.circleId, share.shareId, cursor);
  @override
  String key(CircleMoment item) => item.id;
}

final circleDetailsProvider = FutureProvider.autoDispose
    .family<CircleDetails, String>((ref, id) {
      ref.watch(sessionProvider);
      return ref.watch(circleRepositoryProvider).detail(id);
    });
final circleInvitationProvider = FutureProvider.autoDispose
    .family<CircleInvitation, String>((ref, id) {
      ref.watch(sessionProvider);
      return ref.watch(circleRepositoryProvider).invitation(id);
    });
final circleRestrictionsProvider = FutureProvider.autoDispose
    .family<List<CirclePerson>, String>((ref, id) {
      ref.watch(sessionProvider);
      return ref.watch(circleRepositoryProvider).restrictions(id);
    });

final circleActionsProvider = Provider((ref) => CircleActions(ref));

class CircleActions {
  CircleActions(this._ref);
  final Ref _ref;
  CircleRepository get _repository => _ref.read(circleRepositoryProvider);

  Future<CirclePreview> preview(int code) => _repository.preview(code);

  Future<void> join(int code) async {
    await _repository.join(code);
    _ref.invalidate(circlesProvider);
  }

  Future<String> uploadAvatar(Uint8List bytes, String mime) =>
      _repository.uploadAvatar(bytes, mime);

  Future<void> save(
    String id,
    bool creating,
    String name,
    String? description,
    String? avatarId,
  ) async {
    await _repository.save(
      id: id,
      creating: creating,
      name: name,
      description: description,
      avatarFileId: avatarId,
    );
    _ref.invalidate(circlesProvider);
    _ref.invalidate(circleDetailsProvider(id));
  }

  Future<void> updateInvitation(String id, bool? enabled) async {
    if (enabled == null) {
      await _repository.resetInvitation(id);
    } else {
      await _repository.setInvitation(id, enabled);
    }
    _ref.invalidate(circleInvitationProvider(id));
  }

  Future<void> removeMember(String id, String userId) async {
    await _repository.removeMember(id, userId);
    _ref.invalidate(circleDetailsProvider(id));
    _ref.invalidate(circleRestrictionsProvider(id));
    _ref.invalidate(circleFeedProvider(id));
  }

  Future<void> liftRestriction(String id, String userId) async {
    await _repository.liftRestriction(id, userId);
    _ref.invalidate(circleRestrictionsProvider(id));
  }

  Future<void> exit(String id, {required bool dissolve}) async {
    if (dissolve) {
      await _repository.dissolve(id);
    } else {
      await _repository.leave(id);
    }
    _ref.invalidate(circlesProvider);
    _ref.invalidate(circleFeedProvider(id));
    _ref.invalidate(circleDetailsProvider(id));
  }

  Future<void> withdraw(String id, String shareId) async {
    await _repository.withdraw(id, shareId);
    _ref.invalidate(circleFeedProvider(id));
    _ref.invalidate(sharedTimelineProvider((circleId: id, shareId: shareId)));
  }

  Future<void> share(
    CircleShareType type,
    String id,
    Set<String> targets,
  ) async {
    await _repository.share(type, id, targets);
    for (final circle in targets) {
      _ref.invalidate(circleFeedProvider(circle));
    }
  }
}
