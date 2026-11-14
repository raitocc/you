import 'package:you_app/features/gathering/models/gathering_lifecycle_action.dart';
import 'package:you_app/features/gathering/presentation/viewmodels/gathering_lifecycle_viewmodel.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_join_page.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_invite_page.dart';
import 'package:you_app/features/gathering/presentation/viewmodels/gathering_join_viewmodel.dart';
import 'package:you_app/core/auth/session.dart';
import 'package:you_app/features/moment/models/moment.dart';
import 'package:you_app/features/moment/data/moment_repository.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_moments_page.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_moment_detail_page.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_moment_selection_page.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_picker_page.dart';
import 'package:you_app/features/gathering/presentation/viewmodels/gathering_moments_viewmodel.dart';
import 'package:you_app/features/gathering/presentation/viewmodels/gathering_moment_selection_viewmodel.dart';
import 'package:you_app/core/errors/app_exception.dart';
import 'package:you_app/features/gathering/data/gathering_repository.dart';
import 'package:you_app/features/gathering/data/remote/gathering_remote_data_source.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_create_page.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_detail_page.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_edit_page.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_members_page.dart';
import 'package:you_app/features/gathering/presentation/viewmodels/gathering_edit_viewmodel.dart';
import 'package:you_app/features/gathering/presentation/viewmodels/gathering_members_viewmodel.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_page.dart';
import 'package:you_app/features/gathering/presentation/viewmodels/gathering_list_viewmodel.dart';

const _firstId = '019a0000-0000-7000-8000-000000000001';
const _ownerId = '019a0000-0000-7000-8000-000000000003';
const _secondId = '019a0000-0000-7000-8000-000000000002';
const _time = '2026-11-10T01:02:03.123456789Z';

/// 返回已登录身份，测试使用内存会话。
class _Session extends SessionController {
  @override
  Future<String?> build() async => 'test-token';
}

Map<String, dynamic> _gathering({String id = _firstId, String name = '春日散步'}) =>
    {
      'id': id,
      'status': 'ONGOING',
      'name': name,
      'description': '一起走过的街道',
      'descriptionPreview': '一起走过的街道',
      'creator': null,
      'memberCount': 1,
      'momentCount': 0,
      'inviteCode': 'ABCD1234EFGH5678',
      'createdAt': _time,
      'updatedAt': _time,
    };

Map<String, dynamic> _page({bool hasMore = false}) => {
  'items': [_gathering()],
  'nextCursor': hasMore ? {'createdAt': _time, 'id': _firstId} : null,
  'hasMore': hasMore,
};

/// 选择页只需本人分页数据，其余操作由真实的同行 Repository 执行。
class _Yours implements MomentRepository {
  _Yours(this.load);
  final Future<MomentPageData> Function(MomentFeedCursor?) load;
  @override
  Future<MomentPageData> browseYours({MomentFeedCursor? cursor, int? limit}) =>
      load(cursor);
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  late Dio dio;
  MomentRepository? yours;
  late GatheringRepository repository;
  late List<RequestOptions> requests;
  late Object? Function(RequestOptions request) reply;

  setUp(() {
    yours = null;
    requests = [];
    reply = (_) => _gathering();
    dio = Dio();
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (request, handler) {
          requests.add(request);
          try {
            handler.resolve(
              Response(
                requestOptions: request,
                data: {
                  'code': 'SUCCESS',
                  'message': '操作成功',
                  'data': reply(request),
                },
              ),
            );
          } catch (error) {
            handler.reject(DioException(requestOptions: request, error: error));
          }
        },
      ),
    );
    repository = GatheringRepository(GatheringRemoteDataSource(dio));
    addTearDown(dio.close);
  });

  test('分页游标原样回传时间精度，空创建者资料保留 null', () async {
    reply = (_) => _page(hasMore: true);
    final first = await repository.browse();
    expect(requests.single.queryParameters, isEmpty);
    expect(first.items.single.creator, isNull);
    await repository.browse(cursor: first.nextCursor, limit: 20);
    expect(requests.last.queryParameters, {
      'limit': 20,
      'cursor.createdAt': _time,
      'cursor.id': _firstId,
    });
  });

  test('加载更多失败保留首屏，重试后合并并去重', () async {
    final container = ProviderContainer(
      overrides: [
        sessionProvider.overrideWith(_Session.new),
        gatheringRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(container.dispose);
    await container.read(sessionProvider.future);
    reply = (_) => _page(hasMore: true);
    container.listen(gatheringListProvider, (_, _) {});
    await container.read(gatheringListProvider.future);

    reply = (_) => throw const AppException('FAILED', '请稍后重试');
    final vm = container.read(gatheringListProvider.notifier);
    await vm.loadMore();
    final failed = container.read(gatheringListProvider).requireValue;
    expect(failed.items.single.id, _firstId);
    expect(failed.error, isNotNull);
    expect(failed.hasMore, isTrue);

    reply = (_) => {
      'items': [_gathering(), _gathering(id: _secondId, name: '周末出游')],
      'nextCursor': null,
      'hasMore': false,
    };
    await vm.loadMore();
    final retried = container.read(gatheringListProvider).requireValue;
    expect(retried.items.map((item) => item.id), [_firstId, _secondId]);
    expect(retried.error, isNull);
    expect(retried.hasMore, isFalse);
  });

  Future<GoRouter> showApp(
    WidgetTester tester, {
    String userId = _ownerId,
  }) async {
    final router = GoRouter(
      initialLocation: '/gatherings',
      routes: [
        GoRoute(
          path: '/gatherings',
          builder: (_, _) => const Scaffold(body: GatheringPage()),
        ),
        GoRoute(
          path: '/gatherings/new',
          builder: (_, _) => const GatheringCreatePage(),
        ),
        GoRoute(
          path: '/gatherings/join',
          builder: (_, _) => const GatheringJoinPage(),
        ),
        GoRoute(
          path: '/gatherings/scan',
          builder: (context, _) => Scaffold(
            body: TextButton(
              onPressed: () => context.pop('ABCD1234EFGH5678'),
              child: const Text('模拟识别邀请码'),
            ),
          ),
        ),
        GoRoute(
          path: '/gatherings/:id/invite',
          builder: (_, state) =>
              GatheringInvitePage(id: state.pathParameters['id']!),
        ),
        GoRoute(
          path: '/gatherings/select',
          builder: (_, state) => GatheringPickerPage(
            selectedId: state.uri.queryParameters['selected'],
            momentId: state.uri.queryParameters['momentId'],
          ),
        ),
        GoRoute(
          path: '/gatherings/:id/moments/add',
          builder: (_, state) => GatheringMomentSelectionPage(
            id: state.pathParameters['id']!,
            removing: false,
          ),
        ),
        GoRoute(
          path: '/gatherings/:id/moments/remove',
          builder: (_, state) => GatheringMomentSelectionPage(
            id: state.pathParameters['id']!,
            removing: true,
          ),
        ),
        GoRoute(
          path: '/gatherings/:id/moments/:momentId',
          builder: (_, state) => GatheringMomentDetailPage(
            gatheringId: state.pathParameters['id']!,
            momentId: state.pathParameters['momentId']!,
          ),
        ),
        GoRoute(
          path: '/gatherings/:id/moments',
          builder: (_, state) =>
              GatheringMomentsPage(id: state.pathParameters['id']!),
        ),
        GoRoute(
          path: '/gatherings/:id/edit',
          builder: (_, state) =>
              GatheringEditPage(id: state.pathParameters['id']!),
        ),
        GoRoute(
          path: '/gatherings/:id/members',
          builder: (_, state) =>
              GatheringMembersPage(id: state.pathParameters['id']!),
        ),
        GoRoute(
          path: '/gatherings/:id',
          builder: (_, state) =>
              GatheringDetailPage(id: state.pathParameters['id']!),
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionProvider.overrideWith(_Session.new),
          gatheringRepositoryProvider.overrideWithValue(repository),
          if (yours != null) momentRepositoryProvider.overrideWithValue(yours!),
          gatheringCurrentUserIdProvider.overrideWith((_) async => userId),
        ],
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();
    return router;
  }

  testWidgets('从列表打开详情，摘要与完整介绍分别展示', (tester) async {
    reply = (request) => request.path == '/gatherings' ? _page() : _gathering();
    await showApp(tester);
    expect(find.text('春日散步'), findsOneWidget);
    await tester.tap(find.text('春日散步'));
    await tester.pumpAndSettle();
    expect(find.text('同行信息'), findsOneWidget);
    expect(find.text('关于这次同行'), findsOneWidget);
    expect(find.text('创建者资料暂无'), findsOneWidget);
    expect(requests.last.path, '/gatherings/$_firstId');
  });

  testWidgets('创建校验、成功进入详情并刷新本人列表', (tester) async {
    var created = false;
    reply = (request) {
      if (request.method == 'POST') {
        created = true;
        return _gathering(id: _secondId, name: '毕业旅行');
      }
      if (request.path == '/gatherings') {
        return {
          'items': [
            if (created) _gathering(id: _secondId, name: '毕业旅行'),
            _gathering(),
          ],
          'nextCursor': null,
          'hasMore': false,
        };
      }
      return _gathering(id: _secondId, name: '毕业旅行');
    };
    final router = await showApp(tester);
    await tester.tap(find.text('创建同行'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, '创建同行'));
    await tester.pumpAndSettle();
    expect(find.text('请填写同行名称'), findsOneWidget);
    expect(requests.where((r) => r.method == 'POST'), isEmpty);

    await tester.enterText(find.byType(TextFormField).first, '  毕业旅行  ');
    await tester.tap(find.widgetWithText(FilledButton, '创建同行'));
    await tester.pumpAndSettle();
    expect(requests.singleWhere((r) => r.method == 'POST').data, {
      'name': '毕业旅行',
      'description': null,
    });
    expect(find.text('同行信息'), findsOneWidget);
    expect(find.text('毕业旅行'), findsOneWidget);
    router.pop();
    await tester.pumpAndSettle();
    expect(find.text('春日散步'), findsOneWidget);
    expect(find.text('毕业旅行'), findsOneWidget);
  });

  testWidgets('创建失败保留名称和介绍，页面展示业务提示', (tester) async {
    reply = (request) {
      if (request.method == 'POST') {
        throw const AppException('CONFLICT', '邀请码生成冲突，请重新创建');
      }
      return _page();
    };
    await showApp(tester);
    await tester.tap(find.text('创建同行'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, '周末出游');
    await tester.enterText(find.byType(TextFormField).last, '去公园');
    await tester.tap(find.widgetWithText(FilledButton, '创建同行'));
    await tester.pumpAndSettle();
    expect(find.text('邀请码生成冲突，请重新创建'), findsOneWidget);
    expect(find.text('周末出游'), findsOneWidget);
    expect(find.text('去公园'), findsOneWidget);
  });

  Map<String, dynamic> ownedGathering() => {
    ..._gathering(),
    'creator': {'id': _ownerId, 'nickname': '小满', 'avatarUrl': null},
  };

  test('成员游标使用 userId 并保留连续的空资料项', () async {
    reply = (_) => {
      'items': [null, null],
      'nextCursor': {'createdAt': _time, 'userId': _ownerId},
      'hasMore': true,
    };
    final first = await repository.members(_firstId);
    expect(first.items.length, 2);
    expect(first.items.every((item) => item.user == null), isTrue);
    expect(first.items.map((item) => item.listKey).toSet().length, 2);
    final second = await repository.members(_firstId, cursor: first.nextCursor);
    expect(second.items.first.listKey, isNot(first.items.first.listKey));
    expect(requests.last.path, '/gatherings/$_firstId/members');
    expect(requests.last.queryParameters, {
      'cursor.createdAt': _time,
      'cursor.userId': _ownerId,
    });
  });

  test('成员分页失败后保留资料和空项，重试继续使用原游标', () async {
    final container = ProviderContainer(
      overrides: [
        sessionProvider.overrideWith(_Session.new),
        gatheringRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(container.dispose);
    await container.read(sessionProvider.future);
    reply = (_) => {
      'items': [null, null],
      'nextCursor': {'createdAt': _time, 'userId': _ownerId},
      'hasMore': true,
    };
    final provider = gatheringMembersProvider(_firstId);
    container.listen(provider, (_, _) {});
    await container.read(provider.future);
    final vm = container.read(provider.notifier);
    reply = (_) => throw const AppException('FAILED', '加载失败');
    await vm.loadMore();
    expect(container.read(provider).requireValue.items.length, 2);
    expect(container.read(provider).requireValue.error, isNotNull);
    reply = (_) => {
      'items': [null],
      'nextCursor': null,
      'hasMore': false,
    };
    await vm.loadMore();
    expect(container.read(provider).requireValue.items.length, 3);
    expect(requests.last.queryParameters['cursor.userId'], _ownerId);
  });

  testWidgets('创建者编辑回显，清空介绍后详情与列表同步更新', (tester) async {
    var current = ownedGathering();
    reply = (request) {
      if (request.method == 'PUT') {
        final payload = request.data as Map<String, dynamic>;
        current = {
          ...current,
          ...payload,
          'descriptionPreview': payload['description'],
        };
      }
      if (request.path == '/gatherings') {
        return {
          'items': [current],
          'nextCursor': null,
          'hasMore': false,
        };
      }
      return current;
    };
    final router = await showApp(tester);
    await tester.tap(find.text('春日散步'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('修改同行信息'));
    await tester.pumpAndSettle();
    expect(find.text('一起走过的街道'), findsOneWidget);
    await tester.enterText(find.byType(TextFormField).first, '  夏日出游  ');
    await tester.enterText(find.byType(TextFormField).last, '');
    await tester.tap(find.widgetWithText(FilledButton, '保存修改'));
    await tester.pumpAndSettle();
    expect(requests.singleWhere((r) => r.method == 'PUT').data, {
      'name': '夏日出游',
      'description': null,
    });
    expect(find.text('夏日出游'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('还没有填写介绍'), 150);
    expect(find.text('还没有填写介绍'), findsOneWidget);
    router.pop();
    await tester.pumpAndSettle();
    expect(find.text('夏日出游'), findsOneWidget);
    expect(find.text('春日散步'), findsNothing);
  });

  testWidgets('普通成员保留查看入口，直接进入编辑页显示权限提示', (tester) async {
    reply = (request) => request.path == '/gatherings'
        ? {
            'items': [ownedGathering()],
            'nextCursor': null,
            'hasMore': false,
          }
        : ownedGathering();
    final router = await showApp(tester, userId: _secondId);
    await tester.tap(find.text('春日散步'));
    await tester.pumpAndSettle();
    expect(find.byTooltip('修改同行信息'), findsNothing);
    expect(find.text('查看成员'), findsOneWidget);
    router.push('/gatherings/$_firstId/edit');
    await tester.pumpAndSettle();
    expect(find.text('仅创建者可修改'), findsOneWidget);
    expect(find.byType(TextFormField), findsNothing);
    expect(requests.where((r) => r.method == 'PUT'), isEmpty);
  });

  testWidgets('编辑失败保留输入并展示业务提示', (tester) async {
    reply = (request) {
      if (request.method == 'PUT') {
        throw const AppException('FORBIDDEN', '同行访问权限已变化');
      }
      if (request.path == '/gatherings') {
        return {
          'items': [ownedGathering()],
          'nextCursor': null,
          'hasMore': false,
        };
      }
      return ownedGathering();
    };
    await showApp(tester);
    await tester.tap(find.text('春日散步'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('修改同行信息'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, '新的名字');
    await tester.enterText(find.byType(TextFormField).last, '新的介绍');
    await tester.tap(find.widgetWithText(FilledButton, '保存修改'));
    await tester.pumpAndSettle();
    expect(find.text('同行访问权限已变化'), findsOneWidget);
    expect(find.text('新的名字'), findsOneWidget);
    expect(find.text('新的介绍'), findsOneWidget);
  });

  testWidgets('成员页面展示昵称和空资料项，支持返回详情', (tester) async {
    reply = (request) {
      if (request.path.endsWith('/members')) {
        return {
          'items': [
            null,
            null,
            {'id': _ownerId, 'nickname': '小满', 'avatarUrl': null},
          ],
          'nextCursor': null,
          'hasMore': false,
        };
      }
      return request.path == '/gatherings' ? _page() : _gathering();
    };
    final router = await showApp(tester);
    await tester.tap(find.text('春日散步'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('查看成员'));
    await tester.tap(find.text('查看成员'));
    await tester.pumpAndSettle();
    expect(find.text('用户资料暂无'), findsNWidgets(2));
    expect(find.text('小满'), findsOneWidget);
    expect(find.text('已展示全部成员'), findsOneWidget);
    router.pop();
    await tester.pumpAndSettle();
    expect(find.text('同行信息'), findsOneWidget);
  });

  Map<String, dynamic> joinPreview({bool isMember = false}) => {
    'id': _firstId,
    'name': '春日散步',
    'description': '一起走过的街道',
    'creator': null,
    'isMember': isMember,
  };

  testWidgets('邀请码输入先校验再预览，确认加入刷新列表并进入详情', (tester) async {
    var joined = false;
    reply = (request) {
      if (request.path == '/gatherings/join-preview') return joinPreview();
      if (request.path == '/gatherings/join') {
        joined = true;
        return _gathering();
      }
      if (request.path == '/gatherings') {
        return {
          'items': joined ? [_gathering()] : [],
          'nextCursor': null,
          'hasMore': false,
        };
      }
      return _gathering();
    };
    final router = await showApp(tester, userId: _secondId);
    await tester.tap(find.text('加入同行'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('查看加入预览'));
    await tester.pumpAndSettle();
    expect(find.text('请输入邀请码'), findsOneWidget);
    expect(requests.where((r) => r.path.endsWith('join-preview')), isEmpty);

    await tester.enterText(find.byType(TextFormField), '  ABCD1234EFGH5678  ');
    await tester.tap(find.text('查看加入预览'));
    await tester.pumpAndSettle();
    expect(requests.last.queryParameters, {'inviteCode': 'ABCD1234EFGH5678'});
    expect(find.text('春日散步'), findsOneWidget);
    expect(joined, isFalse);
    await tester.ensureVisible(find.text('确认加入'));
    await tester.tap(find.text('确认加入'));
    await tester.pumpAndSettle();
    expect(requests.singleWhere((r) => r.path == '/gatherings/join').data, {
      'inviteCode': 'ABCD1234EFGH5678',
    });
    expect(find.text('同行信息'), findsOneWidget);
    router.pop();
    await tester.pumpAndSettle();
    expect(find.text('春日散步'), findsOneWidget);
  });

  testWidgets('更换邀请码清除旧预览，查询失败保留输入供重试', (tester) async {
    var fail = false;
    reply = (request) {
      if (request.path == '/gatherings/join-preview') {
        if (fail) throw const AppException('NOT_FOUND', '邀请码无效或加入已关闭');
        return joinPreview();
      }
      return request.path == '/gatherings' ? _page() : _gathering();
    };
    await showApp(tester);
    await tester.tap(find.text('加入同行'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField), 'ABCD1234EFGH5678');
    await tester.tap(find.text('查看加入预览'));
    await tester.pumpAndSettle();
    expect(find.text('确认加入'), findsOneWidget);
    await tester.enterText(find.byType(TextFormField), 'OTHER');
    await tester.pumpAndSettle();
    expect(find.text('确认加入'), findsNothing);
    fail = true;
    await tester.tap(find.text('查看加入预览'));
    await tester.pumpAndSettle();
    expect(find.text('邀请码无效或加入已关闭'), findsOneWidget);
    expect(find.text('OTHER'), findsOneWidget);
    fail = false;
    await tester.tap(find.text('查看加入预览'));
    await tester.pumpAndSettle();
    expect(find.text('确认加入'), findsOneWidget);
    expect(find.text('邀请码无效或加入已关闭'), findsNothing);
  });

  testWidgets('已是成员时直接进入详情，保持成员关系不变', (tester) async {
    reply = (request) {
      if (request.path.endsWith('join-preview')) {
        return joinPreview(isMember: true);
      }
      return request.path == '/gatherings' ? _page() : _gathering();
    };
    await showApp(tester);
    await tester.tap(find.text('加入同行'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField), 'ABCD1234EFGH5678');
    await tester.tap(find.text('查看加入预览'));
    await tester.pumpAndSettle();
    expect(find.text('你已是这个同行的成员'), findsOneWidget);
    await tester.ensureVisible(find.text('进入同行'));
    await tester.tap(find.text('进入同行'));
    await tester.pumpAndSettle();
    expect(find.text('同行信息'), findsOneWidget);
    expect(requests.where((r) => r.method == 'POST'), isEmpty);
  });

  testWidgets('扫码结果回填并预览，加入失败保留目标和邀请码', (tester) async {
    reply = (request) {
      if (request.path.endsWith('join-preview')) return joinPreview();
      if (request.path == '/gatherings/join') {
        throw const AppException('CLOSED', '同行已结束');
      }
      return request.path == '/gatherings' ? _page() : _gathering();
    };
    await showApp(tester);
    await tester.tap(find.text('加入同行'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('扫一扫'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('模拟识别邀请码'));
    await tester.pumpAndSettle();
    expect(find.text('ABCD1234EFGH5678'), findsOneWidget);
    expect(find.text('确认加入'), findsOneWidget);
    expect(requests.where((r) => r.path.endsWith('join-preview')).length, 1);
    expect(requests.where((r) => r.method == 'POST'), isEmpty);
    await tester.ensureVisible(find.text('确认加入'));
    await tester.tap(find.text('确认加入'));
    await tester.pumpAndSettle();
    expect(find.text('同行已结束'), findsOneWidget);
    expect(find.text('ABCD1234EFGH5678'), findsOneWidget);
    expect(find.text('确认加入'), findsOneWidget);
  });

  testWidgets('邀请页面展示二维码并复制原始邀请码', (tester) async {
    String? copied;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copied = (call.arguments as Map)['text'] as String;
        }
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    reply = (request) => request.path == '/gatherings' ? _page() : _gathering();
    await showApp(tester);
    await tester.tap(find.text('春日散步'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('邀请同行'));
    await tester.tap(find.text('邀请同行'));
    await tester.pumpAndSettle();
    expect(find.byType(QrImageView), findsOneWidget);
    expect(find.text('ABCD1234EFGH5678'), findsOneWidget);
    await tester.tap(find.text('复制邀请码'));
    await tester.pumpAndSettle();
    expect(find.text('邀请码已复制'), findsOneWidget);
    expect(copied, 'ABCD1234EFGH5678');
  });

  test('确认加入期间拦截重复提交，成功后恢复可操作状态', () async {
    final container = ProviderContainer(
      overrides: [
        sessionProvider.overrideWith(_Session.new),
        gatheringRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(container.dispose);
    await container.read(sessionProvider.future);
    container.listen(gatheringJoinProvider, (_, _) {});
    reply = (request) =>
        request.path.endsWith('join-preview') ? joinPreview() : _gathering();
    final vm = container.read(gatheringJoinProvider.notifier);
    await vm.preview('ABCD1234EFGH5678');
    final first = vm.join();
    final duplicate = await vm.join();
    expect(duplicate, isNull);
    expect((await first)?.id, _firstId);
    expect(requests.where((r) => r.method == 'POST').length, 1);
    expect(container.read(gatheringJoinProvider).busy, isFalse);
  });

  Map<String, dynamic> summary(String id, {String? authorId = _ownerId}) => {
    'id': id,
    'title': id == _firstId ? '山上的风' : '晚霞',
    'bodyPreview': '正文摘要',
    'coverUrl': null,
    'imageCount': 0,
    'mood': 'HAPPY',
    'memoryTime': _time,
    'author': authorId == null
        ? null
        : {
            'id': authorId,
            'nickname': authorId == _ownerId ? '小满' : '小夏',
            'avatarUrl': null,
          },
  };

  Map<String, dynamic> sharedPage({bool hasMore = false}) => {
    'items': [summary(_firstId)],
    'nextCursor': hasMore ? {'memoryTime': _time, 'id': _firstId} : null,
    'hasMore': hasMore,
  };

  Map<String, dynamic> sharedDetail({String? authorId = _ownerId}) => {
    ...summary(_firstId, authorId: authorId),
    'body': '完整正文',
    'tags': ['旅行'],
    'images': [],
    'location': null,
    'createdAt': _time,
    'updatedAt': _time,
  };

  test('同行摘要保留作者及精确游标，共享详情使用独立路径并保持只读', () async {
    reply = (request) => request.path.endsWith('/$_firstId')
        ? sharedDetail(authorId: null)
        : sharedPage(hasMore: true);
    final page = await repository.moments(_secondId);
    expect(page.items.single.author?.nickname, '小满');
    await repository.moments(_secondId, cursor: page.nextCursor);
    expect(requests.last.queryParameters, {
      'cursor.memoryTime': _time,
      'cursor.id': _firstId,
    });
    final detail = await repository.moment(_secondId, _firstId);
    expect(requests.last.path, '/gatherings/$_secondId/moments/$_firstId');
    expect(detail.author, isNull);
    expect(detail.body, '完整正文');
    expect(detail.canEdit, isFalse);
    expect(detail.canDelete, isFalse);
  });

  test('批量关联冲突保留选择，重试成功后发送一次内容刷新', () async {
    final container = ProviderContainer(
      overrides: [
        sessionProvider.overrideWith(_Session.new),
        gatheringRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(container.dispose);
    await container.read(sessionProvider.future);
    const operation = (gatheringId: _firstId, removing: false);
    final provider = gatheringMomentSelectionProvider(operation);
    container.listen(provider, (_, _) {});
    final vm = container.read(provider.notifier);
    vm.toggle(_firstId);
    vm.toggle(_secondId);
    reply = (_) => throw const AppException('CONFLICT', '请先从原同行移出');
    expect(await vm.submit(), isFalse);
    expect(container.read(provider).selected, {_firstId, _secondId});
    expect(container.read(provider).error, isNotNull);
    expect(container.read(momentRevisionProvider), 0);
    reply = (_) => null;
    final first = vm.submit();
    expect(await vm.submit(), isFalse);
    expect(await first, isTrue);
    expect(requests.last.data, {
      'momentIds': [_firstId, _secondId],
    });
    expect(container.read(momentRevisionProvider), 1);
    expect(container.read(provider).selected, isEmpty);
  });

  test('同行瞬间分页失败保留已有记录，重试沿用发生时间游标', () async {
    final container = ProviderContainer(
      overrides: [
        sessionProvider.overrideWith(_Session.new),
        gatheringRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(container.dispose);
    await container.read(sessionProvider.future);
    final provider = gatheringMomentsProvider(_firstId);
    container.listen(provider, (_, _) {});
    reply = (_) => sharedPage(hasMore: true);
    await container.read(provider.future);
    final vm = container.read(provider.notifier);
    reply = (_) => throw const AppException('FAILED', '稍后重试');
    await vm.loadMore();
    expect(
      container.read(provider).requireValue.items.single.moment.id,
      _firstId,
    );
    reply = (_) => {
      'items': [summary(_secondId, authorId: null)],
      'nextCursor': null,
      'hasMore': false,
    };
    await vm.loadMore();
    expect(requests.last.queryParameters['cursor.memoryTime'], _time);
    expect(container.read(provider).requireValue.items.length, 2);
    expect(container.read(provider).requireValue.items.last.author, isNull);
  });

  testWidgets('共同记录显示作者，打开共享详情保留正文且隐藏编辑操作', (tester) async {
    reply = (request) {
      if (request.path == '/gatherings/$_firstId/moments') return sharedPage();
      if (request.path == '/gatherings/$_firstId/moments/$_firstId') {
        return sharedDetail();
      }
      return request.path == '/gatherings' ? _page() : _gathering();
    };
    await showApp(tester, userId: _secondId);
    await tester.tap(find.text('春日散步'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('浏览共同记录'));
    await tester.pumpAndSettle();
    expect(find.text('小满'), findsOneWidget);
    await tester.tap(find.text('山上的风'));
    await tester.pumpAndSettle();
    expect(find.text('完整正文'), findsOneWidget);
    expect(find.text('同行成员可见'), findsOneWidget);
    expect(find.byTooltip('编辑瞬间'), findsNothing);
    expect(find.byTooltip('关联同行'), findsNothing);
    expect(find.text('查看本人瞬间'), findsNothing);
    expect(requests.where((r) => r.path == '/moments/$_firstId'), isEmpty);
  });

  testWidgets('批量添加跨页保留选择，冲突提示后可以重试并刷新统计', (tester) async {
    var fail = true;
    yours = _Yours(
      (cursor) async => (
        items: [
          (
            id: cursor == null ? _firstId : _secondId,
            title: cursor == null ? '山上的风' : '晚霞',
            bodyPreview: null,
            coverUrl: null,
            imageCount: 0,
            mood: null,
            memoryTime: DateTime.parse(_time),
          ),
        ],
        nextCursor: cursor == null ? (memoryTime: _time, id: _firstId) : null,
        hasMore: cursor == null,
      ),
    );
    reply = (request) {
      if (request.method == 'POST') {
        if (fail) throw const AppException('CONFLICT', '请先从原同行移出');
        return null;
      }
      return request.path == '/gatherings' ? _page() : _gathering();
    };
    await showApp(tester);
    await tester.tap(find.text('春日散步'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('添加本人瞬间'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('山上的风'));
    await tester.pump();
    await tester.tap(find.text('加载更多瞬间'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('晚霞'));
    await tester.pump();
    await tester.tap(find.text('添加已选的 2 个瞬间'));
    await tester.pumpAndSettle();
    expect(find.text('请先从原同行移出'), findsOneWidget);
    expect(
      tester
          .widgetList<CheckboxListTile>(find.byType(CheckboxListTile))
          .every((tile) => tile.value == true),
      isTrue,
    );
    fail = false;
    await tester.tap(find.text('添加已选的 2 个瞬间'));
    await tester.pumpAndSettle();
    expect(find.text('同行信息'), findsOneWidget);
    expect(requests.lastWhere((r) => r.method == 'POST').data, {
      'momentIds': [_firstId, _secondId],
    });
    expect(
      requests
          .where((r) => r.path == '/gatherings/$_firstId' && r.method == 'GET')
          .length,
      greaterThan(1),
    );
  });

  testWidgets('移出页只允许选择本人记录，确认后调用批量移出路径', (tester) async {
    reply = (request) {
      if (request.method == 'POST') return null;
      if (request.path.endsWith('/moments')) {
        return {
          'items': [summary(_firstId), summary(_secondId, authorId: _secondId)],
          'nextCursor': null,
          'hasMore': false,
        };
      }
      return request.path == '/gatherings' ? _page() : _gathering();
    };
    final router = await showApp(tester);
    router.push('/gatherings/$_firstId/moments/remove');
    await tester.pumpAndSettle();
    final tiles = tester
        .widgetList<CheckboxListTile>(find.byType(CheckboxListTile))
        .toList();
    expect(tiles.first.onChanged, isNotNull);
    expect(tiles.last.onChanged, isNull);
    await tester.tap(find.text('山上的风'));
    await tester.pump();
    await tester.tap(find.text('移出已选的 1 个瞬间'));
    await tester.pumpAndSettle();
    expect(requests.where((r) => r.method == 'POST'), isEmpty);
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(find.text('移出已选的 1 个瞬间'), findsOneWidget);
    await tester.tap(find.text('移出已选的 1 个瞬间'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认移出'));
    await tester.pumpAndSettle();
    expect(
      requests.singleWhere((r) => r.method == 'POST').path,
      '/gatherings/$_firstId/moments/remove',
    );
    expect(requests.singleWhere((r) => r.method == 'POST').data, {
      'momentIds': [_firstId],
    });
    expect(requests.where((r) => r.method == 'DELETE'), isEmpty);
  });

  testWidgets('已有瞬间选择同行后完成关联并返回原页', (tester) async {
    reply = (request) => request.method == 'POST' ? null : _page();
    final router = await showApp(tester);
    router.push('/gatherings/select?momentId=$_secondId');
    await tester.pumpAndSettle();
    await tester.tap(find.text('春日散步'));
    await tester.pumpAndSettle();
    expect(find.text('一起经历的日子'), findsOneWidget);
    expect(find.text('选择同行'), findsNothing);
    expect(requests.singleWhere((r) => r.method == 'POST').data, {
      'momentIds': [_secondId],
    });
  });

  test('生命周期请求使用约定的方法和路径，携带空请求体', () async {
    reply = (_) => null;
    await repository.leave(_firstId);
    await repository.end(_firstId);
    await repository.dissolve(_firstId);
    expect(requests.map((r) => '${r.method} ${r.path}'), [
      'POST /gatherings/$_firstId/leave',
      'POST /gatherings/$_firstId/end',
      'DELETE /gatherings/$_firstId',
    ]);
    expect(requests.every((r) => r.data == null), isTrue);
  });

  test('生命周期失败保留错误，重试期间阻止交叉提交，成功广播刷新', () async {
    final container = ProviderContainer(
      overrides: [
        sessionProvider.overrideWith(_Session.new),
        gatheringRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(container.dispose);
    await container.read(sessionProvider.future);
    final provider = gatheringLifecycleProvider(_firstId);
    container.listen(provider, (_, _) {});
    final vm = container.read(provider.notifier);
    reply = (_) => throw const AppException('FAILED', '请稍后重试');
    expect(await vm.execute(GatheringLifecycleAction.leave), isFalse);
    expect(container.read(provider).hasError, isTrue);
    expect(container.read(momentRevisionProvider), 0);
    reply = (_) => null;
    final first = vm.execute(GatheringLifecycleAction.leave);
    expect(await vm.execute(GatheringLifecycleAction.dissolve), isFalse);
    expect(await first, isTrue);
    expect(container.read(momentRevisionProvider), 1);
    expect(requests.length, 2);
  });

  testWidgets('普通成员退出先确认，取消保留详情，成功返回刷新后的列表', (tester) async {
    var left = false;
    reply = (r) {
      if (r.path.endsWith('/leave')) {
        left = true;
        return null;
      }
      if (r.path == '/gatherings') {
        return left
            ? {'items': [], 'nextCursor': null, 'hasMore': false}
            : _page();
      }
      return ownedGathering();
    };
    await showApp(tester, userId: _secondId);
    await tester.tap(find.text('春日散步'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('同行操作'));
    await tester.pumpAndSettle();
    expect(find.text('结束同行'), findsNothing);
    expect(find.text('解散同行'), findsNothing);
    await tester.tap(find.text('退出同行'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(requests.where((r) => r.method == 'POST'), isEmpty);
    await tester.tap(find.byTooltip('同行操作'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('退出同行'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认退出同行'));
    await tester.pumpAndSettle();
    expect(find.text('一起经历的日子'), findsOneWidget);
    expect(find.text('春日散步'), findsNothing);
    expect(requests.where((r) => r.method == 'DELETE'), isEmpty);
  });

  testWidgets('创建者结束后刷新状态，收起邀请添加和结束操作，保留浏览与解散', (tester) async {
    var ended = false;
    reply = (r) {
      if (r.path.endsWith('/end')) {
        ended = true;
        return null;
      }
      final item = {...ownedGathering(), 'status': ended ? 'ENDED' : 'ONGOING'};
      return r.path == '/gatherings'
          ? {
              'items': [item],
              'nextCursor': null,
              'hasMore': false,
            }
          : item;
    };
    await showApp(tester);
    await tester.tap(find.text('春日散步'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('同行操作'));
    await tester.pumpAndSettle();
    expect(find.text('退出同行'), findsNothing);
    await tester.tap(find.text('结束同行'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认结束同行'));
    await tester.pumpAndSettle();
    expect(find.text('同行信息'), findsOneWidget);
    expect(find.text('已结束'), findsOneWidget);
    expect(find.text('添加本人瞬间'), findsNothing);
    expect(find.text('邀请同行'), findsNothing);
    expect(find.text('浏览共同记录'), findsOneWidget);
    await tester.tap(find.byTooltip('同行操作'));
    await tester.pumpAndSettle();
    expect(find.text('结束同行'), findsNothing);
    expect(find.text('解散同行'), findsOneWidget);
  });

  testWidgets('解散失败留在详情并提示，重试成功返回列表且保留原瞬间', (tester) async {
    var fail = true;
    var dissolved = false;
    reply = (r) {
      if (r.method == 'DELETE') {
        if (fail) throw const AppException('FAILED', '解散失败，请重试');
        dissolved = true;
        return null;
      }
      if (r.path == '/gatherings') {
        return {
          'items': [if (!dissolved) ownedGathering()],
          'nextCursor': null,
          'hasMore': false,
        };
      }
      return ownedGathering();
    };
    await showApp(tester);
    await tester.tap(find.text('春日散步'));
    await tester.pumpAndSettle();
    Future<void> confirmDissolve() async {
      await tester.tap(find.byTooltip('同行操作'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('解散同行'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('确认解散同行'));
      await tester.pumpAndSettle();
    }

    await confirmDissolve();
    expect(find.text('解散失败，请重试'), findsOneWidget);
    expect(find.text('同行信息'), findsOneWidget);
    fail = false;
    await confirmDissolve();
    expect(find.text('一起经历的日子'), findsOneWidget);
    expect(find.text('春日散步'), findsNothing);
    expect(requests.where((r) => r.path.startsWith('/moments')), isEmpty);
  });

  testWidgets('已结束同行在选择页禁用，直接进入邀请和添加页展示结束提示', (tester) async {
    final item = {...ownedGathering(), 'status': 'ENDED'};
    reply = (r) => r.path == '/gatherings'
        ? {
            'items': [item],
            'nextCursor': null,
            'hasMore': false,
          }
        : item;
    final router = await showApp(tester);
    router.push('/gatherings/select?momentId=$_secondId');
    await tester.pumpAndSettle();
    final tile = tester.widget<ListTile>(find.widgetWithText(ListTile, '春日散步'));
    expect(tile.onTap, isNull);
    router.go('/gatherings/$_firstId/invite');
    await tester.pumpAndSettle();
    expect(find.text('同行已结束'), findsOneWidget);
    expect(find.byType(QrImageView), findsNothing);
    router.go('/gatherings/$_firstId/moments/add');
    await tester.pumpAndSettle();
    expect(find.text('同行已结束'), findsOneWidget);
    expect(find.byType(CheckboxListTile), findsNothing);
  });
}
