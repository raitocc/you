import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:you_app/app/theme/app_theme.dart';
import 'package:you_app/core/auth/session.dart';
import 'package:you_app/core/network/api_client.dart';
import 'package:you_app/features/album/data/album_inputs.dart';
import 'package:you_app/features/album/data/album_repository.dart';
import 'package:you_app/features/album/data/remote/album_remote_data_source.dart';
import 'package:you_app/features/album/presentation/pages/album_editor_page.dart';
import 'package:you_app/features/album/presentation/pages/album_detail_page.dart';
import 'package:you_app/features/album/presentation/viewmodels/album_viewmodels.dart';

class TestSession extends SessionController {
  @override
  Future<String?> build() async => 'token';
  void change() => state = const AsyncData('other');
}

Map<String, dynamic> albumJson() => {
  'id': '019aa3c0-0000-7000-8000-000000000001',
  'name': '山野之间',
  'description': null,
  'coverFileId': null,
  'coverUrl': null,
  'organizeMode': 'manual',
  'momentCount': 0,
  'createdAt': '2026-11-08T00:00:00Z',
  'updatedAt': '2026-11-08T00:00:00Z',
};
AlbumInputs inputs() => AlbumInputs(
  moments: (_) async => (
    items: [(id: 'moment-1', title: '山间散步', preview: null, coverUrl: null)],
    cursor: null,
    hasMore: false,
  ),
  upload: (_, _) async => 'cover-1',
  isReady: (_) async => true,
);
void main() {
  test(
    'pending create cannot submit twice or retain data after session changes',
    () async {
      final dio = Dio();
      addTearDown(dio.close);
      final response = Completer<void>();
      final started = Completer<void>();
      var calls = 0;
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (request, handler) async {
            calls++;
            started.complete();
            await response.future;
            handler.resolve(
              Response(
                requestOptions: request,
                data: {'code': 'SUCCESS', 'data': albumJson()},
              ),
            );
          },
        ),
      );
      final container = ProviderContainer(
        overrides: [
          dioProvider.overrideWithValue(dio),
          sessionProvider.overrideWith(TestSession.new),
        ],
      );
      addTearDown(container.dispose);
      await container.read(sessionProvider.future);
      final subscription = container.listen(albumEditorProvider, (_, _) {});
      addTearDown(subscription.close);
      final vm = container.read(albumEditorProvider.notifier);
      final pending = vm.create('山野之间', '');
      expect(await vm.create('再次提交', ''), isNull);
      await started.future;
      expect(calls, 1);
      (container.read(sessionProvider.notifier) as TestSession).change();
      container.read(albumEditorProvider);
      response.complete();
      expect(await pending, isNull);
      expect(container.read(recentAlbumsProvider), isEmpty);
      expect(container.read(albumEditorProvider).busy, isFalse);
    },
  );
  test(
    'create and detail match backend payload and nullable response',
    () async {
      final dio = Dio();
      addTearDown(dio.close);
      final requests = <RequestOptions>[];
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (r, h) {
            requests.add(r);
            h.resolve(
              Response(
                requestOptions: r,
                data: {'code': 'SUCCESS', 'data': albumJson()},
              ),
            );
          },
        ),
      );
      final repo = AlbumRepository(AlbumRemoteDataSource(dio), inputs);
      final created = await repo.create(
        name: '  山野之间  ',
        description: '',
        momentIds: ['m1', 'm1'],
      );
      expect(requests.single.method, 'POST');
      expect(requests.single.path, '/albums');
      expect(requests.single.data, {
        'name': '山野之间',
        'description': null,
        'coverFileId': null,
        'momentIds': ['m1'],
      });
      expect(created.coverUrl, isNull);
      expect(created.createdAt.isUtc, isTrue);
      final detail = await repo.detail(created.id);
      expect(requests.last.method, 'GET');
      expect(requests.last.path, '/albums/${created.id}');
      expect(detail.organizeMode, 'manual');
      await expectLater(
        repo.create(name: ' ', description: '', momentIds: []),
        throwsA(anything),
      );
      expect(requests.length, 2);
    },
  );

  testWidgets(
    'validate, choose moments, create and view album on a small screen',
    (tester) async {
      tester.view.physicalSize = const Size(375, 812);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final dio = Dio();
      addTearDown(dio.close);
      Map<String, dynamic>? payload;
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (r, h) {
            if (r.method == 'POST') payload = r.data as Map<String, dynamic>;
            h.resolve(
              Response(
                requestOptions: r,
                data: {'code': 'SUCCESS', 'data': albumJson()},
              ),
            );
          },
        ),
      );
      final container = ProviderContainer(
        overrides: [
          dioProvider.overrideWithValue(dio),
          sessionProvider.overrideWith(TestSession.new),
          albumInputsProvider.overrideWithValue(inputs()),
        ],
      );
      addTearDown(container.dispose);
      await container.read(sessionProvider.future);
      final router = GoRouter(
        initialLocation: '/albums/new',
        routes: [
          GoRoute(
            path: '/albums/new',
            builder: (_, _) => const AlbumEditorPage(),
          ),
          GoRoute(
            path: '/albums/:id',
            builder: (_, s) => AlbumDetailPage(id: s.pathParameters['id']!),
          ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp.router(
            theme: AppTheme.light,
            routerConfig: router,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('创建时光'));
      await tester.pumpAndSettle();
      expect(find.text('请填写时光名称'), findsOneWidget);
      expect(payload, isNull);
      await tester.enterText(find.byType(TextFormField).first, '山野之间');
      await tester.ensureVisible(find.text('山间散步'));
      await tester.tap(find.text('山间散步'));
      await tester.tap(find.text('创建时光'));
      await tester.pumpAndSettle();
      expect(payload?['momentIds'], ['moment-1']);
      expect(find.text('时光信息'), findsOneWidget);
      expect(find.text('山野之间'), findsOneWidget);
      tester.view.physicalSize = const Size(812, 375);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(container.read(recentAlbumsProvider).length, 1);
    },
  );
}
