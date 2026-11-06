import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:you_app/app/app.dart';
import 'package:you_app/app/router/app_router.dart';
import 'package:you_app/core/auth/session.dart';
import 'package:you_app/core/network/api_client.dart';
import 'package:you_app/core/storage/app_database.dart';
import 'package:you_app/features/moment/data/local/moment_draft_data_source.dart';
import 'package:you_app/features/moment/data/mappers/moment_draft_mapper.dart';
import 'package:you_app/features/moment/data/mappers/moment_mapper.dart';
import 'package:you_app/features/moment/models/moment.dart';
import 'package:you_app/features/moment/models/moment_draft.dart';
import 'package:you_app/features/user/models/user_profile.dart';
import 'package:you_app/features/user/presentation/viewmodels/profile_viewmodel.dart';

class FakeSession extends SessionController {
  FakeSession(this.token);
  final String? token;
  @override
  Future<String?> build() async => token;
  void signIn() {
    state = const AsyncData('test-token');
  }

  @override
  Future<void> clear() async {
    state = const AsyncData(null);
  }
}

class FakeProfile extends ProfileViewModel {
  @override
  Future<ProfileCardData> build() async => (
    user: const UserProfile(id: '1', username: 'user', nickname: 'User'),
    edits: (nickname: null, signature: '', avatar: null, background: null),
  );

  void setAvatar(Uint8List avatar) {
    final current = state.requireValue;
    state = AsyncData((
      user: current.user,
      edits: (
        nickname: current.edits.nickname,
        signature: current.edits.signature,
        avatar: avatar,
        background: current.edits.background,
      ),
    ));
  }
}

class MemoryMomentDrafts extends MomentDraftDataSource {
  MemoryMomentDrafts(super.database);
  MomentDraft? draft;

  @override
  Future<MomentDraft?> read(String owner, String key, Moment? current) async =>
      draft;

  @override
  Future<void> save(String owner, String key, MomentDraft draft) async {
    this.draft = draft;
  }

  @override
  Future<void> remove(String owner, String key, String draftId) async {
    draft = null;
  }
}

void main() {
  test('saved publication and compact moment response match the API', () {
    const id = '019aa3c0-0000-7000-8000-000000000001';
    const draft = MomentDraft(
      id: id,
      pending: {
        'body': '原发布内容',
        'mood': '开心',
        'circleIds': [],
        'imageFileIds': ['file-1'],
      },
    );
    final payload = createMomentPayload(draft);
    expect(payload['mood'], 'HAPPY');
    expect(payload['imageFileIds'], ['file-1']);
    expect(payload, isNot(contains('circleIds')));
    expect(createMomentPayload(draft), payload);

    final result = momentFromJson(
      {
        'id': id,
        'title': null,
        'body': payload['body'],
        'mood': payload['mood'],
        'images': [
          {'fileId': 'file-1', 'url': 'http://storage/image'},
        ],
        'tags': [],
        'memoryTime': payload['memoryTime'],
        'location': null,
        'createdAt': '2026-11-06T10:00:00.000Z',
        'updatedAt': '2026-11-06T10:00:00.000Z',
      },
      currentAuthor: (id: 'owner-1', nickname: null, avatarUrl: null),
    );
    expect(result.author.id, 'owner-1');
    expect(result.images.single.id, 'file-1');
    expect(result.mood, '开心');
    expect(result.isOwn, isTrue);
  });

  testWidgets('anonymous deep link resumes after authentication', (
    tester,
  ) async {
    final container = ProviderContainer(
      overrides: [sessionProvider.overrideWith(() => FakeSession(null))],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const YouApp()),
    );
    await tester.pumpAndSettle();
    final router = container.read(routerProvider);
    router.go('/albums/42');
    await tester.pumpAndSettle();
    expect(find.text('登录'), findsOneWidget);
    (container.read(sessionProvider.notifier) as FakeSession).signIn();
    await tester.pumpAndSettle();
    expect(router.routeInformationProvider.value.uri.path, '/albums/42');
    expect(find.text('瞬间'), findsNothing);
  });
  testWidgets('root moment editor, publishing, detail, editing and deletion', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final avatar = File('test/fixtures/avatar.png').readAsBytesSync();
    final database = AppDatabase.forTesting(NativeDatabase.memory());
    addTearDown(database.close);
    final drafts = MemoryMomentDrafts(database);
    Map<String, Object?>? moment;
    var publicationRequests = 0;
    final dio = Dio();
    addTearDown(dio.close);
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (request, handler) {
          final path = request.path;
          Object? data;
          if (path == '/users/me') {
            data = {'id': '1', 'username': 'user', 'nickname': 'User'};
          } else if (path == '/moments' && request.method == 'GET') {
            data = {
              'items': [
                if (moment != null && request.queryParameters['tab'] == 'yours')
                  moment,
              ],
              'nextCursor': null,
              'hasMore': false,
            };
          } else if (path == '/moments' && request.method == 'POST') {
            publicationRequests++;
            final input = request.data as Map<String, dynamic>;
            final id = input['id'] as String;
            expect(id[14], '7');
            expect(DateTime.parse(input['memoryTime'] as String).isUtc, isTrue);
            expect(input, isNot(contains('journeyId')));
            expect(input, isNot(contains('circleIds')));
            expect(drafts.draft?.pending?['id'], id);
            moment = {
              ...input,
              'author': {'id': '1', 'nickname': 'User'},
              'images': [],
              'sources': [],
              'collections': [],
              'isOwn': true,
              'canEdit': true,
              'canDelete': true,
              'memoryTime': '2026-11-06T10:00:00.000Z',
              'updatedAt': '2026-11-06T10:00:00.000Z',
            };
            handler.reject(
              DioException(
                requestOptions: request,
                type: DioExceptionType.receiveTimeout,
              ),
            );
            return;
          } else if (path.startsWith('/moments/') && moment != null) {
            if (request.method == 'PATCH') {
              expect(request.headers['If-Match'], '"2026-11-06T10:00:00.000Z"');
              moment = {
                ...moment!,
                ...request.data as Map<String, dynamic>,
                'updatedAt': '2026-11-06T10:01:00.000Z',
              };
            }
            if (request.method == 'DELETE') {
              expect(request.headers['If-Match'], '"2026-11-06T10:01:00.000Z"');
              moment = null;
              data = {'deleted': true};
            } else if (request.method == 'GET') {
              data = {
                for (final field in [
                  'id',
                  'title',
                  'body',
                  'images',
                  'tags',
                  'mood',
                  'memoryTime',
                  'location',
                  'updatedAt',
                ])
                  field: moment![field],
                'createdAt': '2026-11-06T10:00:00.000Z',
              };
            } else {
              data = moment;
            }
          }
          handler.resolve(
            Response<dynamic>(
              requestOptions: request,
              statusCode: 200,
              data: {'code': 'SUCCESS', 'data': data},
            ),
          );
        },
      ),
    );
    final container = ProviderContainer(
      overrides: [
        sessionProvider.overrideWith(() => FakeSession('test-token')),
        profileProvider.overrideWith(FakeProfile.new),
        dioProvider.overrideWithValue(dio),
        momentDraftDataSourceProvider.overrideWithValue(drafts),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const YouApp()),
    );
    await tester.pumpAndSettle();
    expect(find.text('与你有关'), findsOneWidget);
    final profileButton = find.byTooltip('个人资料');
    expect(
      find.descendant(
        of: profileButton,
        matching: find.byIcon(Icons.account_circle_outlined),
      ),
      findsOneWidget,
    );
    (container.read(profileProvider.notifier) as FakeProfile).setAvatar(avatar);
    await tester.pumpAndSettle();
    final avatarImage = find.descendant(
      of: profileButton,
      matching: find.byType(Image),
    );
    expect(tester.widget<Image>(avatarImage).image, MemoryImage(avatar));
    await tester.tap(find.text('时光').last);
    await tester.pumpAndSettle();
    expect(
      container.read(routerProvider).routeInformationProvider.value.uri.path,
      '/albums',
    );
    await tester.tap(find.byTooltip('发布瞬间'));
    await tester.pumpAndSettle();
    expect(find.text('新的瞬间'), findsOneWidget);
    expect(find.byTooltip('个人资料'), findsNothing);
    await tester.enterText(find.widgetWithText(TextFormField, '记录此刻'), '初次记录');
    await tester.tap(find.text('发布'));
    await tester.pumpAndSettle();
    expect(drafts.draft?.pending, isNotNull);
    await tester.tap(find.text('确认发布'));
    await tester.pumpAndSettle();
    expect(publicationRequests, 1);
    expect(find.text('初次记录'), findsOneWidget);
    expect(drafts.draft, isNull);
    await tester.tap(find.byTooltip('返回'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('瞬间').last);
    await tester.pumpAndSettle();
    expect(find.text('自己发布的所有瞬间'), findsOneWidget);
    await tester.tap(find.text('初次记录'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('编辑瞬间'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextFormField, '记录此刻'), '更新记录');
    await tester.tap(find.text('保存'));
    await tester.pumpAndSettle();
    expect(find.text('更新记录'), findsOneWidget);
    await tester.tap(find.byTooltip('更多操作'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除瞬间'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除'));
    await tester.pumpAndSettle();
    expect(moment, isNull);
    expect(find.byTooltip('个人资料'), findsOneWidget);
  });
}
