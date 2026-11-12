import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/network/api_client.dart';
import '../../../shared/models/cursor_page.dart';
import '../models/circle.dart';
import 'mappers/circle_mapper.dart';
import 'remote/circle_remote.dart';

typedef CircleAvatarUpload = Future<String> Function(Uint8List, String);
final circleAvatarUploadProvider = Provider<CircleAvatarUpload>(
  (_) => throw StateError('Circle avatar upload must be assembled by app'),
);

final circleRepositoryProvider = Provider(
  (ref) => CircleRepository(
    CircleRemote(ref.watch(dioProvider)),
    () => ref.read(circleAvatarUploadProvider),
  ),
);

class CircleRepository {
  CircleRepository(this._remote, this._upload);
  final CircleRemote _remote;
  final CircleAvatarUpload Function() _upload;

  Future<BrowsePage<T, CircleCursor>> _browse<T>(
    String path,
    String timeKey,
    CircleCursor? cursor,
    T Function(Json) decode,
  ) async => circlePage(
    await _remote.get(
          path,
          query: {
            if (cursor != null) ...{
              'cursor.$timeKey': cursor.time,
              'cursor.id': cursor.id,
            },
          },
        )
        as Json,
    timeKey,
    decode,
  );

  Future<BrowsePage<CircleSummary, CircleCursor>> browse(
    CircleCursor? cursor,
  ) => _browse('', 'joinedAt', cursor, circleSummary);

  Future<BrowsePage<CircleShare, CircleCursor>> feed(
    String id,
    CircleCursor? cursor,
  ) => _browse('/$id/shares', 'sharedAt', cursor, circleShare);

  Future<BrowsePage<CircleMoment, CircleCursor>> timeline(
    String id,
    String shareId,
    CircleCursor? cursor,
  ) => _browse(
    '/$id/shares/$shareId/moments',
    'memoryTime',
    cursor,
    circleMoment,
  );

  Future<CircleDetails> detail(String id) async =>
      circleDetails(await _remote.get('/$id') as Json);

  Future<CirclePreview> preview(int code) async => circlePreview(
    await _remote.post('/join-preview', {'inviteCode': code}) as Json,
  );

  Future<void> join(int code) async {
    await _remote.post('/join', {'inviteCode': code});
  }

  Future<void> save({
    required String id,
    required bool creating,
    required String name,
    required String? description,
    required String? avatarFileId,
  }) async {
    final body = {
      'name': name,
      'description': description,
      'avatarFileId': avatarFileId,
    };
    if (creating) {
      await _remote.post('', {'id': id, ...body});
    } else {
      await _remote.put('/$id', body);
    }
  }

  Future<String> uploadAvatar(Uint8List bytes, String mime) =>
      _upload()(bytes, mime);
  Future<CircleInvitation> invitation(String id) async =>
      circleInvitation(await _remote.get('/$id/invitation') as Json);
  Future<void> setInvitation(String id, bool enabled) async {
    await _remote.put('/$id/invitation', {'inviteEnabled': enabled});
  }

  Future<void> resetInvitation(String id) async {
    await _remote.post('/$id/invitation/reset');
  }

  Future<List<CirclePerson>> restrictions(String id) async => List.unmodifiable(
    (await _remote.get('/$id/join-restrictions') as List).cast<Json>().map(
      (item) => circlePerson(item['user'] as Json),
    ),
  );
  Future<void> liftRestriction(String id, String userId) =>
      _remote.delete('/$id/join-restrictions/$userId');
  Future<void> removeMember(String id, String userId) =>
      _remote.delete('/$id/members/$userId');
  Future<void> leave(String id) => _remote.delete('/$id/members/me');
  Future<void> dissolve(String id) => _remote.delete('/$id');
  Future<void> withdraw(String id, String shareId) =>
      _remote.delete('/$id/shares/$shareId');

  Future<void> share(
    CircleShareType type,
    String objectId,
    Set<String> circleIds,
  ) async {
    await _remote.post('/shares', {
      'objectType': type.name,
      'objectId': objectId,
      'circleIds': circleIds.toList(),
    });
  }
}
