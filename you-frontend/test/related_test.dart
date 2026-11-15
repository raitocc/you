import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:you_app/core/auth/session.dart';
import 'package:you_app/features/related/data/related_repository.dart';
import 'package:you_app/features/related/data/remote/related_remote_data_source.dart';
import 'package:you_app/features/related/models/related_entry.dart';
import 'package:you_app/features/related/presentation/pages/related_feed_page.dart';
import 'package:you_app/features/related/presentation/viewmodels/related_feed_viewmodel.dart';

const _time = '2026-11-14T01:02:03.123456789Z';

class _Session extends SessionController {
  @override
  Future<String?> build() async => 'test-token';
}

Map<String, dynamic> _entry(
  String id, {
  bool album = false,
  bool circle = false,
}) => {
  'id': id,
  'sharedAt': _time,
  'objectType': album ? 'album' : 'moment',
  'source': {
    'type': circle ? 'circle' : 'gathering',
    'id': circle ? 'c1' : 'g1',
    'name': circle ? '朋友圈' : '周末出游',
  },
  'shareId': circle ? 'share-1' : null,
  'actor': {'id': 'user-1', 'nickname': '小林', 'avatarUrl': null},
  'moment': album
      ? null
      : {
          'id': 'same-moment',
          'title': '沿河散步',
          'bodyPreview': '午后沿着河岸走走',
          'coverUrl': null,
          'imageCount': 0,
          'mood': null,
          'memoryTime': '2020-01-01T00:00:00Z',
          'author': null,
        },
  'album': album
      ? {
          'id': 'album-1',
          'name': '秋日小记',
          'descriptionPreview': '几次周末的风景',
          'coverUrl': null,
          'momentCount': 3,
          'owner': null,
        }
      : null,
};

Map<String, dynamic> _page(List<Map<String, dynamic>> items, {String? next}) =>
    {
      'items': items,
      'nextCursor': next == null ? null : {'sharedAt': _time, 'id': next},
      'hasMore': next != null,
    };

void main() {
  late Dio dio;
  late RelatedRepository repository;
  late List<RequestOptions> requests;
  late FutureOr<Map<String, dynamic>> Function(RequestOptions) reply;

  setUp(() {
    requests = [];
    reply = (_) => _page([]);
    dio = Dio();
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (request, handler) async {
          requests.add(request);
          try {
            handler.resolve(
              Response(
                requestOptions: request,
                data: {'code': 'SUCCESS', 'data': await reply(request)},
              ),
            );
          } catch (error) {
            handler.reject(DioException(requestOptions: request, error: error));
          }
        },
      ),
    );
    repository = RelatedRepository(RelatedRemoteDataSource(dio));
  });
  tearDown(() => dio.close(force: true));

  Future<ProviderContainer> container() async {
    final result = ProviderContainer(
      overrides: [
        sessionProvider.overrideWith(_Session.new),
        relatedRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(result.dispose);
    await result.read(sessionProvider.future);
    final subscription = result.listen(relatedFeedProvider, (_, _) {});
    addTearDown(subscription.close);
    await result.read(relatedFeedProvider.future);
    return result;
  }

  test('GET 分页原样回传时间游标，解析混合摘要与来源', () async {
    reply = (_) => _page([
      _entry('inbox-1'),
      _entry('inbox-2', album: true, circle: true),
    ], next: 'inbox-2');
    final first = await repository.browse();
    expect(requests.single.path, '/moments/related');
    expect(requests.single.method, 'GET');
    expect(requests.single.data, isNull);
    expect(requests.single.queryParameters, isEmpty);
    expect(first.items.first, isA<RelatedMomentEntry>());
    final album = first.items.last as RelatedAlbumEntry;
    expect(album.album.momentCount, 3);
    expect(album.source.type, RelatedSourceType.circle);
    expect(album.shareId, 'share-1');
    expect(album.album.owner, isNull);
    await repository.browse(cursor: first.nextCursor, limit: 10);
    expect(requests.last.queryParameters, {
      'cursor.sharedAt': _time,
      'cursor.id': 'inbox-2',
      'limit': 10,
    });
  });

  test('追加按动态 ID 去重，同一瞬间的不同分享分别保留', () async {
    reply = (request) => request.queryParameters.isEmpty
        ? _page([_entry('inbox-1')], next: 'inbox-1')
        : _page([_entry('inbox-1'), _entry('inbox-2', circle: true)]);
    final scope = await container();
    await scope.read(relatedFeedProvider.notifier).loadMore();
    final feed = scope.read(relatedFeedProvider).requireValue;
    expect(feed.items.map((entry) => entry.id), ['inbox-1', 'inbox-2']);
    expect(feed.hasMore, isFalse);
  });

  test('追加失败保留列表和游标，重试后继续加载', () async {
    reply = (_) => _page([_entry('inbox-1')], next: 'inbox-1');
    final scope = await container();
    reply = (_) => throw Exception('断网');
    await scope.read(relatedFeedProvider.notifier).loadMore();
    final failed = scope.read(relatedFeedProvider).requireValue;
    expect(failed.items, hasLength(1));
    expect(failed.moreError, isNotNull);
    expect(failed.cursor?.id, 'inbox-1');
    reply = (_) => _page([_entry('inbox-2', album: true, circle: true)]);
    await scope.read(relatedFeedProvider.notifier).loadMore();
    expect(scope.read(relatedFeedProvider).requireValue.items, hasLength(2));
    expect(scope.read(relatedFeedProvider).requireValue.moreError, isNull);
  });

  test('刷新失败保留旧卡片，刷新成功替换列表', () async {
    reply = (_) => _page([_entry('inbox-1')]);
    final scope = await container();
    reply = (_) => throw Exception('断网');
    await scope.read(relatedFeedProvider.notifier).refresh();
    expect(
      scope.read(relatedFeedProvider).requireValue.items.single.id,
      'inbox-1',
    );
    expect(
      scope.read(relatedFeedProvider).requireValue.refreshError,
      isNotNull,
    );
    reply = (_) => _page([_entry('inbox-2')]);
    await scope.read(relatedFeedProvider.notifier).refresh();
    expect(
      scope.read(relatedFeedProvider).requireValue.items.single.id,
      'inbox-2',
    );
  });

  test('刷新后旧的追加请求停止写入列表', () async {
    reply = (_) => _page([_entry('inbox-1')], next: 'inbox-1');
    final scope = await container();
    final waiting = Completer<Map<String, dynamic>>();
    final requested = Completer<void>();
    reply = (request) {
      if (request.queryParameters.isNotEmpty) {
        requested.complete();
        return waiting.future;
      }
      return _page([_entry('inbox-new')]);
    };
    final more = scope.read(relatedFeedProvider.notifier).loadMore();
    await requested.future;
    await scope.read(relatedFeedProvider.notifier).refresh();
    waiting.complete(_page([_entry('inbox-old')]));
    await more;
    expect(
      scope.read(relatedFeedProvider).requireValue.items.single.id,
      'inbox-new',
    );
  });

  testWidgets('混合卡片展示来源，点击来源并返回后保留列表', (tester) async {
    reply = (_) => _page([
      _entry('inbox-1'),
      _entry('inbox-2', album: true, circle: true),
    ]);
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (_, _) => const Scaffold(body: RelatedFeedPage()),
        ),
        GoRoute(
          path: '/gatherings/:id',
          builder: (_, state) => Scaffold(
            appBar: AppBar(title: Text('同行详情 ${state.pathParameters['id']}')),
          ),
        ),
        GoRoute(
          path: '/circles',
          builder: (_, state) => Scaffold(
            appBar: AppBar(
              title: Text('圈子 ${state.uri.queryParameters['selected']}'),
            ),
          ),
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionProvider.overrideWith(_Session.new),
          relatedRepositoryProvider.overrideWithValue(repository),
        ],
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('沿河散步'), findsOneWidget);
    expect(find.text('秋日小记'), findsOneWidget);
    expect(find.text('时光 · 3 个瞬间'), findsOneWidget);
    await tester.tap(find.text('同行 · 周末出游'));
    await tester.pumpAndSettle();
    expect(find.text('同行详情 g1'), findsOneWidget);
    router.pop();
    await tester.pumpAndSettle();
    expect(find.text('沿河散步'), findsOneWidget);
    await tester.tap(find.text('圈子 · 朋友圈'));
    await tester.pumpAndSettle();
    expect(find.text('圈子 c1'), findsOneWidget);
    expect(requests, hasLength(1));
  });

  testWidgets('空列表呈现引导，首屏失败显示重试并可恢复', (tester) async {
    reply = (_) => throw Exception('断网');
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionProvider.overrideWith(_Session.new),
          relatedRepositoryProvider.overrideWithValue(repository),
        ],
        child: const MaterialApp(home: Scaffold(body: RelatedFeedPage())),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('动态加载失败'), findsOneWidget);
    reply = (_) => _page([]);
    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();
    expect(find.text('还没有与你有关的动态'), findsOneWidget);
  });
  testWidgets('切换 Tab 保留滚动位置和已加载列表', (tester) async {
    reply = (_) => _page(List.generate(20, (index) => _entry('inbox-$index')));
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionProvider.overrideWith(_Session.new),
          relatedRepositoryProvider.overrideWithValue(repository),
        ],
        child: const MaterialApp(
          home: Scaffold(
            body: DefaultTabController(
              length: 2,
              child: Column(
                children: [
                  TabBar(
                    tabs: [
                      Tab(text: '你'),
                      Tab(text: '与你有关'),
                    ],
                  ),
                  Expanded(
                    child: TabBarView(
                      children: [Text('本人列表'), RelatedFeedPage()],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('与你有关'));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(CustomScrollView), const Offset(0, -500));
    await tester.pumpAndSettle();
    final scroll = tester
        .state<ScrollableState>(find.byType(Scrollable).last)
        .position;
    final offset = scroll.pixels;
    expect(offset, greaterThan(0));
    await tester.tap(find.text('你'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('与你有关'));
    await tester.pumpAndSettle();
    expect(scroll.pixels, offset);
    expect(requests, hasLength(1));
  });
  for (final kind in ['gathering', 'circle', 'album']) {
    testWidgets('$kind 内容卡片进入对应共享详情路径', (tester) async {
      final isAlbum = kind == 'album';
      reply = (_) => _page([
        _entry('inbox-1', circle: kind != 'gathering', album: isAlbum),
      ]);
      final path = switch (kind) {
        'gathering' => '/gatherings/g1/moments/same-moment',
        'circle' => '/circles/c1/shares/share-1/moment',
        _ => '/circles/c1/shares/share-1',
      };
      final router = GoRouter(
        routes: [
          GoRoute(
            path: '/',
            builder: (_, _) => const Scaffold(body: RelatedFeedPage()),
          ),
          GoRoute(
            path: path,
            builder: (_, _) => const Scaffold(body: Text('共享内容详情')),
          ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sessionProvider.overrideWith(_Session.new),
            relatedRepositoryProvider.overrideWithValue(repository),
          ],
          child: MaterialApp.router(routerConfig: router),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text(isAlbum ? '秋日小记' : '沿河散步'));
      await tester.pumpAndSettle();
      expect(find.text('共享内容详情'), findsOneWidget);
      router.pop();
      await tester.pumpAndSettle();
      expect(find.text(isAlbum ? '秋日小记' : '沿河散步'), findsOneWidget);
    });
  }
}
