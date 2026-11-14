import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:you_app/core/auth/session.dart';
import 'package:you_app/core/errors/app_exception.dart';
import 'package:you_app/core/location/device_location_service.dart';
import 'package:you_app/features/gathering/data/gathering_repository.dart';
import 'package:you_app/features/gathering/data/remote/gathering_remote_data_source.dart';
import 'package:you_app/features/gathering/data/mappers/face_to_face_mapper.dart';
import 'package:you_app/features/gathering/models/face_to_face.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_page.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_create_page.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_detail_page.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_face_page.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_face_create_page.dart';
import 'package:you_app/features/gathering/presentation/pages/gathering_face_join_page.dart';
import 'package:you_app/features/gathering/presentation/viewmodels/gathering_edit_viewmodel.dart';
import 'package:you_app/features/gathering/presentation/viewmodels/gathering_face_join_viewmodel.dart';
import 'package:you_app/features/gathering/presentation/viewmodels/gathering_face_create_viewmodel.dart';
import 'package:you_app/features/gathering/presentation/widgets/face_to_face_candidate_card.dart';

const _id = '019a0000-0000-7000-8000-000000000001';
const _otherId = '019a0000-0000-7000-8000-000000000002';

class _Session extends SessionController {
  @override
  Future<String?> build() async => 'test-token';
}

/// 流程测试替换设备定位，保留真实数据源、模型转换和状态管理。
class _Location extends DeviceLocationService {
  Object? error;
  int calls = 0;
  @override
  Future<DeviceCoordinates> current() async {
    calls++;
    if (error != null) throw error!;
    return (latitude: 31.2, longitude: 121.5);
  }
}

String _future() =>
    DateTime.now().toUtc().add(const Duration(minutes: 5)).toIso8601String();
Map<String, dynamic> _receipt() => {
  'gatheringId': _id,
  'faceCode': '0005',
  'expiresAt': _future(),
};
Map<String, dynamic> _candidate({String id = _id, bool member = false}) => {
  'gathering': {
    'id': id,
    'name': id == _id ? '散步' : '晚霞',
    'description': '一起走走',
    'creator': null,
  },
  'distanceMeters': 12.5,
  'expiresAt': _future(),
  'isMember': member,
};
Map<String, dynamic> _detail({String id = _id}) => {
  'id': id,
  'name': id == _id ? '散步' : '晚霞',
  'description': '一起走走',
  'descriptionPreview': '一起走走',
  'creator': {'id': _id, 'nickname': '小满', 'avatarUrl': null},
  'memberCount': 1,
  'momentCount': 0,
  'status': 'ONGOING',
  'inviteCode': 'ABCD1234EFGH5678',
  'createdAt': DateTime.now().toUtc().toIso8601String(),
  'updatedAt': DateTime.now().toUtc().toIso8601String(),
};
Map<String, dynamic> _page() => {
  'items': [_detail()],
  'nextCursor': null,
  'hasMore': false,
};

void main() {
  late Dio dio;
  late GatheringRepository repository;
  late _Location location;
  late List<RequestOptions> requests;
  late Object? Function(RequestOptions) reply;
  setUp(() {
    location = _Location();
    requests = [];
    dio = Dio();
    reply = (r) =>
        r.path == '/gatherings' && r.method == 'GET' ? _page() : _detail();
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
                  'message': '成功',
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
  ProviderContainer container() {
    final result = ProviderContainer(
      overrides: [
        sessionProvider.overrideWith(_Session.new),
        gatheringRepositoryProvider.overrideWithValue(repository),
        deviceLocationProvider.overrideWithValue(location),
      ],
    );
    addTearDown(result.dispose);
    return result;
  }

  Future<void> showApp(WidgetTester tester) async {
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
          path: '/gatherings/face-to-face',
          builder: (_, _) => const GatheringFacePage(),
        ),
        GoRoute(
          path: '/gatherings/face-to-face/create',
          builder: (_, _) => const GatheringFaceCreatePage(),
        ),
        GoRoute(
          path: '/gatherings/face-to-face/join',
          builder: (_, _) => const GatheringFaceJoinPage(),
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
          deviceLocationProvider.overrideWithValue(location),
          gatheringCurrentUserIdProvider.overrideWith((_) async => _id),
        ],
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> search(WidgetTester tester) async {
    await tester.tap(find.text('面对面'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(OutlinedButton, '加入同行'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField), '0005');
    await tester.tap(find.text('查找附近同行'));
    await tester.pumpAndSettle();
  }

  test('四位短码保留前导零，预览保留所有候选，加入只传 ID 和短码', () async {
    expect(FaceToFaceRules.validateCode('0005'), isNull);
    expect(FaceToFaceRules.validateCode('005'), isNotNull);
    expect(FaceToFaceRules.validateCode('１２３４'), isNotNull);
    reply = (r) => r.method == 'GET'
        ? [_candidate(), _candidate(id: _otherId, member: true)]
        : r.path.endsWith('/join')
        ? _detail()
        : _receipt();
    const coordinates = (latitude: 31.2, longitude: 121.5);
    expect(
      (await repository.openFaceToFace(_id, '0005', coordinates)).faceCode,
      '0005',
    );
    expect(requests.last.data, {
      'faceCode': '0005',
      'latitude': 31.2,
      'longitude': 121.5,
    });
    final targets = await repository.previewFaceToFace('0005', coordinates);
    expect(targets.length, 2);
    expect(targets.first.gathering.creator, isNull);
    expect(targets.last.isMember, isTrue);
    expect(requests.last.queryParameters, {
      'faceCode': '0005',
      'latitude': 31.2,
      'longitude': 121.5,
    });
    await repository.joinFaceToFace(_otherId, '0005');
    expect(requests.last.data, {'gatheringId': _otherId, 'faceCode': '0005'});
  });

  test('定位失败先保留输入，恢复后创建并开启，提交期间拦住重复操作', () async {
    final ref = container();
    await ref.read(sessionProvider.future);
    ref.listen(gatheringFaceCreateProvider, (_, _) {});
    final vm = ref.read(gatheringFaceCreateProvider.notifier);
    location.error = const AppException('LOCATION_DENIED', '请授权定位');
    expect(await vm.create('散步', '', '0005'), isNull);
    expect(ref.read(gatheringFaceCreateProvider).error, isNotNull);
    expect(requests, isEmpty);
    location.error = null;
    reply = (r) => r.path.endsWith('/face-to-face') ? _receipt() : _detail();
    final first = vm.create('散步', '', '0005');
    expect(await vm.create('散步', '', '0005'), isNull);
    expect((await first)?.id, _id);
    expect(requests.where((r) => r.path == '/gatherings').length, 1);
    expect(requests.where((r) => r.path.endsWith('/face-to-face')).length, 1);
    expect(location.calls, 2);
  });

  test('创建成功后开启失败，重试沿用原同行只请求开启', () async {
    final ref = container();
    await ref.read(sessionProvider.future);
    ref.listen(gatheringFaceCreateProvider, (_, _) {});
    final vm = ref.read(gatheringFaceCreateProvider.notifier);
    var fail = true;
    reply = (r) {
      if (r.path.endsWith('/face-to-face')) {
        if (fail) throw const AppException('FAILED', '请重试开启');
        return _receipt();
      }
      return _detail();
    };
    expect(await vm.create('散步', '', '0005'), isNull);
    expect(ref.read(gatheringFaceCreateProvider).created?.id, _id);
    fail = false;
    expect((await vm.create('散步', '', '0005'))?.id, _id);
    expect(requests.where((r) => r.path == '/gatherings').length, 1);
    expect(requests.where((r) => r.path.endsWith('/face-to-face')).length, 2);
  });

  test('加入失败保留候选，重试沿用已预览短码，修改输入清除旧目标', () async {
    final ref = container();
    await ref.read(sessionProvider.future);
    ref.listen(gatheringFaceJoinProvider, (_, _) {});
    final vm = ref.read(gatheringFaceJoinProvider.notifier);
    reply = (_) => [_candidate(), _candidate(id: _otherId)];
    await vm.preview('0005');
    final selected = ref.read(gatheringFaceJoinProvider).candidates.last;
    reply = (_) => throw const AppException('EXPIRED', '短码已过期');
    expect(await vm.join(selected), isNull);
    expect(ref.read(gatheringFaceJoinProvider).candidates.length, 2);
    expect(ref.read(gatheringFaceJoinProvider).error, isNotNull);
    reply = (_) => _detail(id: _otherId);
    expect((await vm.join(selected))?.id, _otherId);
    expect(requests.last.data, {'gatheringId': _otherId, 'faceCode': '0005'});
    expect(location.calls, 1);
    vm.changeCode('0006');
    expect(ref.read(gatheringFaceJoinProvider).candidates, isEmpty);
    expect(ref.read(gatheringFaceJoinProvider).searched, isFalse);
  });

  testWidgets('面对面先选角色，两步填写后发起成功直接进入详情', (tester) async {
    final normal = reply;
    reply = (r) => r.path.endsWith('/face-to-face') ? _receipt() : normal(r);
    await showApp(tester);
    await tester.tap(find.text('面对面'));
    await tester.pumpAndSettle();
    expect(find.text('发起同行'), findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, '加入同行'), findsOneWidget);
    expect(find.byType(TextFormField), findsNothing);
    await tester.tap(find.text('发起同行'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, '散步');
    await tester.enterText(find.byType(TextFormField).last, '沿河走走');
    await tester.tap(find.text('下一步'));
    await tester.pumpAndSettle();
    expect(requests.where((r) => r.method == 'POST'), isEmpty);
    expect(location.calls, 0);
    await tester.enterText(find.byType(TextFormField), '0005');
    await tester.tap(find.byType(BackButton));
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<TextFormField>(find.byType(TextFormField).first)
          .controller
          ?.text,
      '散步',
    );
    expect(
      tester
          .widget<TextFormField>(find.byType(TextFormField).last)
          .controller
          ?.text,
      '沿河走走',
    );
    await tester.tap(find.text('下一步'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<TextFormField>(find.byType(TextFormField)).controller?.text,
      '0005',
    );
    await tester.tap(find.text('确认发起'));
    await tester.pumpAndSettle();
    expect(find.text('同行信息'), findsOneWidget);
    expect(find.text('设置面对面短码'), findsNothing);
    expect(find.byTooltip('面对面短码'), findsNothing);
    expect(
      requests
          .singleWhere((r) => r.method == 'POST' && r.path == '/gatherings')
          .data,
      {'name': '散步', 'description': '沿河走走'},
    );
    expect(requests.singleWhere((r) => r.path.endsWith('/face-to-face')).data, {
      'faceCode': '0005',
      'latitude': 31.2,
      'longitude': 121.5,
    });
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('多个候选逐项展示，确认第二个后进入对应同行', (tester) async {
    final normal = reply;
    reply = (r) => r.path.endsWith('/join-preview')
        ? [_candidate(), _candidate(id: _otherId)]
        : r.path.endsWith('/join')
        ? _detail(id: _otherId)
        : r.path == '/gatherings/$_otherId'
        ? _detail(id: _otherId)
        : normal(r);
    await showApp(tester);
    await search(tester);
    expect(find.byType(FaceToFaceCandidateCard), findsNWidgets(2));
    await tester.ensureVisible(find.text('确认加入').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认加入').last);
    await tester.pumpAndSettle();
    expect(requests.singleWhere((r) => r.path.endsWith('/join')).data, {
      'gatheringId': _otherId,
      'faceCode': '0005',
    });
    expect(find.text('同行信息'), findsOneWidget);
    expect(find.text('晚霞'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('定位权限错误提供设置入口，授权后可重试，空结果有提示', (tester) async {
    final normal = reply;
    reply = (r) => r.path.endsWith('/join-preview') ? [] : normal(r);
    location.error = const AppException(
      'LOCATION_DENIED_FOREVER',
      '请在应用设置中授权定位',
    );
    await showApp(tester);
    await search(tester);
    expect(find.text('打开应用设置'), findsOneWidget);
    expect(requests.where((r) => r.path.endsWith('/join-preview')), isEmpty);
    location.error = null;
    await tester.tap(find.text('查找附近同行'));
    await tester.pumpAndSettle();
    expect(find.textContaining('附近暂无有效同行'), findsOneWidget);
    reply = (r) =>
        r.path.endsWith('/join-preview') ? [_candidate()] : normal(r);
    await tester.tap(find.text('查找附近同行'));
    await tester.pumpAndSettle();
    expect(find.byType(FaceToFaceCandidateCard), findsOneWidget);
    await tester.enterText(find.byType(TextFormField), '0006');
    await tester.pump();
    expect(find.byType(FaceToFaceCandidateCard), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('倒计时过期后停用加入，已有成员仍可进入', (tester) async {
    final json = _candidate();
    json['expiresAt'] = DateTime.now()
        .add(const Duration(seconds: 2))
        .toIso8601String();
    var confirmed = false;
    Future<void> show() => tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: FaceToFaceCandidateCard(
            candidate: faceToFaceCandidateFromJson(json),
            busy: false,
            onConfirm: () => confirmed = true,
          ),
        ),
      ),
    );
    await show();
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNotNull,
    );
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(seconds: 3)),
    );
    await tester.pump(const Duration(seconds: 1));
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
    expect(confirmed, isFalse);
    json['isMember'] = true;
    await show();
    await tester.tap(find.text('进入同行'));
    expect(confirmed, isTrue);
    await tester.pumpWidget(const SizedBox());
  });
}
