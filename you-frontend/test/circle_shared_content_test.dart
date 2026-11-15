import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:you_app/app/router/app_router.dart';
import 'package:you_app/core/auth/session.dart';
import 'package:you_app/core/errors/app_exception.dart';
import 'package:you_app/core/network/api_client.dart';
import 'package:you_app/features/circle/data/circle_repository.dart';
import 'package:you_app/features/circle/data/remote/circle_remote.dart';
import 'package:you_app/features/circle/presentation/viewmodels/shared_content_viewmodels.dart';

const _time = '2026-11-14T01:02:03.123456789Z';
const _base = '/circles/c1/shares/s1';

class _Session extends SessionController {
  @override
  Future<String?> build() async => 'test-token';
}

Map<String, dynamic> _moment({String id = 'm1'}) => {
  'id': id,
  'title': '完整瞬间',
  'body': '共享正文的完整内容',
  'author': {'id': 'u1', 'nickname': '小林', 'avatarUrl': null},
  'images': <dynamic>[],
  'tags': ['周末'],
  'mood': null,
  'memoryTime': _time,
  'location': null,
  'updatedAt': _time,
};

Map<String, dynamic> _album() => {
  'id': 'a1',
  'name': '最新时光名称',
  'description': '完整的时光介绍',
  'coverFileId': null,
  'coverUrl': null,
  'organizeMode': 'manual',
  'momentCount': 2,
  'createdAt': _time,
  'updatedAt': _time,
};

Map<String, dynamic> _page({bool more = false, String id = 'm1'}) => {
  'items': [_moment(id: id)],
  'hasMore': more,
  'nextCursor': more ? {'memoryTime': _time, 'id': id} : null,
};

void main() {
  late Dio dio;
  late List<RequestOptions> requests;
  late FutureOr<Object?> Function(RequestOptions) reply;
  late CircleRepository repository;

  setUp(() {
    requests = [];
    reply = (request) => switch (request.path) {
      '$_base/album' => _album(),
      '$_base/moments' => _page(),
      _ => _moment(),
    };
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
    repository = CircleRepository(
      CircleRemote(dio),
      () => throw UnimplementedError(),
    );
  });
  tearDown(() => dio.close(force: true));

  Future<ProviderContainer> open(WidgetTester tester, String path) async {
    final scope = ProviderContainer(
      overrides: [
        sessionProvider.overrideWith(_Session.new),
        dioProvider.overrideWithValue(dio),
      ],
    );
    addTearDown(scope.dispose);
    await scope.read(sessionProvider.future);
    final router = scope.read(routerProvider);
    router.go(path);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: scope,
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();
    return scope;
  }

  test('三个详情读取使用圈子分享路径，瞬间模型保持只读', () async {
    final moment = await repository.sharedMoment('c1', 's1');
    final album = await repository.sharedAlbum('c1', 's1');
    final inner = await repository.sharedAlbumMoment('c1', 's1', 'm1');
    expect(requests.map((request) => request.path), [
      '$_base/moment',
      '$_base/album',
      '$_base/moments/m1',
    ]);
    expect(
      requests.every(
        (request) => request.method == 'GET' && request.data == null,
      ),
      isTrue,
    );
    expect(moment.author?.nickname, '小林');
    expect(moment.isOwn, isFalse);
    expect(moment.canEdit, isFalse);
    expect(inner.canDelete, isFalse);
    expect(album.description, '完整的时光介绍');
  });

  testWidgets('真实路由打开直分享瞬间，展示作者与完整正文且保持只读', (tester) async {
    await open(tester, '$_base/moment');
    expect(find.text('完整瞬间'), findsOneWidget);
    expect(find.text('共享正文的完整内容'), findsOneWidget);
    expect(find.text('小林'), findsOneWidget);
    expect(find.text('圈子成员可见'), findsOneWidget);
    expect(find.byTooltip('编辑'), findsNothing);
    expect(find.byTooltip('删除'), findsNothing);
    expect(requests.map((request) => request.path), ['$_base/moment']);
  });

  testWidgets('共享时光读取完整信息，分页后点击内部瞬间进入来源详情', (tester) async {
    reply = (request) {
      if (request.path == '$_base/album') return _album();
      if (request.path == '$_base/moments') {
        return request.queryParameters.isEmpty
            ? _page(more: true)
            : _page(id: 'm2');
      }
      return _moment(id: 'm2');
    };
    final scope = await open(tester, _base);
    expect(find.text('最新时光名称'), findsOneWidget);
    expect(find.text('完整的时光介绍'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('加载更多'), 200);
    await tester.tap(find.text('加载更多'));
    await tester.pumpAndSettle();
    final pageRequest = requests.last;
    expect(pageRequest.path, '$_base/moments');
    expect(pageRequest.queryParameters, {
      'cursor.memoryTime': _time,
      'cursor.id': 'm1',
    });
    await tester.scrollUntilVisible(find.text('查看完整瞬间').last, 200);
    await tester.tap(find.text('查看完整瞬间').last);
    await tester.pumpAndSettle();
    expect(requests.last.path, '$_base/moments/m2');
    expect(find.text('圈子成员可见'), findsOneWidget);
    scope.read(routerProvider).pop();
    await tester.pumpAndSettle();
    expect(find.text('最新时光名称'), findsOneWidget);
  });

  testWidgets('分享撤回时展示服务端信息，重试可恢复详情', (tester) async {
    reply = (_) => throw const AppException('NOT_FOUND', '分享已撤回');
    await open(tester, '$_base/moment');
    expect(find.text('无法查看共享瞬间'), findsOneWidget);
    expect(find.text('分享已撤回'), findsOneWidget);
    reply = (_) => _moment();
    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();
    expect(find.text('完整瞬间'), findsOneWidget);
  });

  testWidgets('空的共享时光仍显示完整介绍和空状态', (tester) async {
    reply = (request) => request.path == '$_base/album'
        ? {..._album(), 'momentCount': 0}
        : {'items': <dynamic>[], 'nextCursor': null, 'hasMore': false};
    await open(tester, _base);
    expect(find.text('完整的时光介绍'), findsOneWidget);
    expect(find.text('这段时光还没有瞬间'), findsOneWidget);
  });

  test('相同内容在不同分享来源下分别加载详情', () async {
    final scope = ProviderContainer(
      overrides: [
        sessionProvider.overrideWith(_Session.new),
        circleRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(scope.dispose);
    await scope.read(sessionProvider.future);
    final first = circleSharedMomentProvider((
      circleId: 'c1',
      shareId: 's1',
      momentId: null,
    ));
    final second = circleSharedMomentProvider((
      circleId: 'c2',
      shareId: 's2',
      momentId: null,
    ));
    final one = scope.listen(first, (_, _) {});
    final two = scope.listen(second, (_, _) {});
    addTearDown(one.close);
    addTearDown(two.close);
    await Future.wait([scope.read(first.future), scope.read(second.future)]);
    expect(
      requests.map((request) => request.path),
      containsAll([
        '/circles/c1/shares/s1/moment',
        '/circles/c2/shares/s2/moment',
      ]),
    );
  });
}
