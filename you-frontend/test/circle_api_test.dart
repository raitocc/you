import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:you_app/features/circle/data/circle_repository.dart';
import 'package:you_app/features/circle/data/remote/circle_remote.dart';
import 'package:you_app/features/circle/models/circle.dart';

void main() {
  late CircleRepository repository;
  late List<RequestOptions> requests;
  Object? response;
  const time = '2026-11-10T01:02:03.123456789Z';

  setUp(() {
    requests = [];
    response = null;
    final dio = Dio();
    addTearDown(dio.close);
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (request, handler) {
          requests.add(request);
          handler.resolve(
            Response(
              requestOptions: request,
              data: {'code': 'SUCCESS', 'data': response},
            ),
          );
        },
      ),
    );
    repository = CircleRepository(
      CircleRemote(dio),
      () => throw UnimplementedError(),
    );
  });

  for (final timeKey in ['joinedAt', 'sharedAt', 'memoryTime']) {
    test('circle pagination forwards $timeKey without truncating it', () async {
      response = {
        'items': <Object>[],
        'nextCursor': {timeKey: time, 'id': 'last'},
        'hasMore': true,
      };
      final (
        String path,
        Future<({CircleCursor? nextCursor, bool hasMore})> Function(
          CircleCursor?,
        )
        fetch,
      ) = switch (timeKey) {
        'joinedAt' => (
          '/circles',
          (cursor) async {
            final page = await repository.browse(cursor);
            return (nextCursor: page.nextCursor, hasMore: page.hasMore);
          },
        ),
        'sharedAt' => (
          '/circles/circle/shares',
          (cursor) async {
            final page = await repository.feed('circle', cursor);
            return (nextCursor: page.nextCursor, hasMore: page.hasMore);
          },
        ),
        _ => (
          '/circles/circle/shares/share/moments',
          (cursor) async {
            final page = await repository.timeline('circle', 'share', cursor);
            return (nextCursor: page.nextCursor, hasMore: page.hasMore);
          },
        ),
      };
      final first = await fetch(null);
      expect(first.hasMore, isTrue);
      await fetch(first.nextCursor);
      expect(requests.last.path, path);
      expect(requests.last.queryParameters, {
        'cursor.$timeKey': time,
        'cursor.id': 'last',
      });
    });
  }

  test('invitation reads a string code and preserves disabled null', () async {
    response = {'inviteEnabled': true, 'inviteCode': 'ABCD1234EFGH5678'};
    final enabled = await repository.invitation('circle');
    expect(enabled.code, 'ABCD1234EFGH5678');
    response = {'inviteEnabled': false, 'inviteCode': null};
    final disabled = await repository.invitation('circle');
    expect(disabled.enabled, isFalse);
    expect(disabled.code, isNull);
  });

  test('join sends a string invite code', () async {
    await repository.join('ABCD1234EFGH5678');
    expect(requests.single.path, '/circles/join');
    expect(requests.single.data, {'inviteCode': 'ABCD1234EFGH5678'});
  });

  test(
    'sharing uses a single batch and withdrawing uses the share id',
    () async {
      await repository.share(CircleShareType.album, 'album', {'one', 'two'});
      await repository.withdraw('one', 'share');
      expect(requests.first.path, '/circles/shares');
      expect(requests.first.data, {
        'objectType': 'album',
        'objectId': 'album',
        'circleIds': ['one', 'two'],
      });
      expect(requests.last.method, 'DELETE');
      expect(requests.last.path, '/circles/one/shares/share');
    },
  );
}
