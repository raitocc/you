import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:you_app/features/moment/data/mappers/moment_mapper.dart';
import 'package:you_app/features/moment/data/remote/moment_remote_data_source.dart';
import 'package:you_app/features/moment/presentation/states/moment_feed_state.dart';

void main() {
  test(
    'feed uses GET query fields and round-trips structured cursors through the last page',
    () async {
      const cursor = {
        'memoryTime': '2026-11-08T01:02:03.123Z',
        'id': '019aa3c0-0000-7000-8000-000000000001',
      };
      final dio = Dio();
      addTearDown(dio.close);
      final requests = <RequestOptions>[];
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (request, handler) {
            requests.add(request);
            handler.resolve(
              Response(
                requestOptions: request,
                data: {
                  'code': 'SUCCESS',
                  'data': {
                    'items': <dynamic>[],
                    'nextCursor': requests.length == 1 ? cursor : null,
                    'hasMore': requests.length == 1,
                  },
                },
              ),
            );
          },
        ),
      );
      final remote = MomentRemoteDataSource(dio);
      final first = momentPageFromJson(await remote.browseYours());
      expect(requests.first.method, 'GET');
      expect(requests.first.path, '/moments/yours');
      expect(requests.first.queryParameters, isEmpty);
      expect(requests.first.data, isNull);
      expect(momentFeedState(first).hasMore, isTrue);
      final same = momentPageFromJson({
        'items': <dynamic>[],
        'nextCursor': Map<String, dynamic>.from(cursor),
        'hasMore': true,
      });
      expect(first.nextCursor, same.nextCursor);
      final last = momentPageFromJson(
        await remote.browseYours(cursor: first.nextCursor, limit: 10),
      );
      expect(requests.last.queryParameters, {
        'cursor.memoryTime': cursor['memoryTime'],
        'cursor.id': cursor['id'],
        'limit': 10,
      });
      expect(requests.last.data, isNull);
      expect(last.nextCursor, isNull);
      expect(momentFeedState(last).hasMore, isFalse);
    },
  );
}
