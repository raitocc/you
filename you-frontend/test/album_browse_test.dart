import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:you_app/features/album/data/album_repository.dart';
import 'package:you_app/features/album/data/remote/album_remote_data_source.dart';

void main() {
  for (final timeline in [false, true]) {
    test(
      'GET album pagination timeline=$timeline preserves precise cursors',
      () async {
        final dio = Dio();
        addTearDown(dio.close);
        final requests = <RequestOptions>[];
        const instant = '2026-11-08T01:02:03.123456789Z';
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
                      'items': timeline && requests.length == 1
                          ? [
                              {
                                'id': 'moment',
                                'title': null,
                                'body': '正文',
                                'images': [
                                  {
                                    'fileId': 'file',
                                    'url': 'https://example.com/image',
                                  },
                                ],
                                'tags': <String>[],
                                'mood': null,
                                'memoryTime': instant,
                                'location': null,
                              },
                            ]
                          : <dynamic>[],
                      'nextCursor': requests.length == 1
                          ? {
                              'createdAt': instant,
                              'id': 'id',
                              if (timeline) 'memoryTime': instant,
                            }
                          : null,
                      'hasMore': requests.length == 1,
                    },
                  },
                ),
              );
            },
          ),
        );
        final repository = AlbumRepository(
          AlbumRemoteDataSource(dio),
          () => throw UnimplementedError(),
        );
        if (timeline) {
          final first = await repository.browseMoments('album');
          expect(first.items.single.body, '正文');
          expect(first.items.single.images, ['https://example.com/image']);
          final last = await repository.browseMoments(
            'album',
            cursor: first.nextCursor,
            limit: 7,
          );
          expect(last.hasMore, false);
          expect(last.nextCursor, isNull);
        } else {
          final first = await repository.browse();
          expect(first.items, isEmpty);
          final last = await repository.browse(
            cursor: first.nextCursor,
            limit: 7,
          );
          expect(last.hasMore, false);
          expect(last.nextCursor, isNull);
        }
        expect(
          requests.first.path,
          timeline ? '/albums/album/moments' : '/albums',
        );
        expect(requests.first.method, 'GET');
        expect(requests.first.queryParameters, isEmpty);
        expect(requests.last.data, isNull);
        expect(requests.last.queryParameters, {
          'limit': 7,
          'cursor.createdAt': instant,
          'cursor.id': 'id',
          if (timeline) 'cursor.memoryTime': instant,
        });
      },
    );
  }
}
