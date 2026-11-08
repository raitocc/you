import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:you_app/app/theme/app_theme.dart';
import 'package:you_app/core/auth/session.dart';
import 'package:you_app/core/network/api_client.dart';
import 'package:you_app/features/album/data/album_repository.dart';
import 'package:you_app/features/album/data/mappers/album_mapper.dart';
import 'package:you_app/features/album/data/remote/album_remote_data_source.dart';
import 'package:you_app/features/album/presentation/pages/album_editor_page.dart';
import 'package:you_app/features/album/presentation/pages/album_detail_page.dart';
import 'package:you_app/features/album/presentation/viewmodels/album_viewmodels.dart';

class _Session extends SessionController {
  @override
  Future<String?> build() async => 'token';
  void change() => state = const AsyncData('other');
}

Map<String, dynamic> _album() => {
  'id': '019aa3c0-0000-7000-8000-000000000001',
  'name': '山野之间',
  'description': '原介绍',
  'coverFileId': 'cover',
  'coverUrl': null,
  'organizeMode': 'manual',
  'momentCount': 0,
  'createdAt': '2026-11-08T00:00:00Z',
  'updatedAt': '2026-11-08T00:00:00Z',
};
void _resolve(RequestOptions r, RequestInterceptorHandler h, Object? data) =>
    h.resolve(
      Response(requestOptions: r, data: {'code': 'SUCCESS', 'data': data}),
    );
Future<void> _preview(WidgetTester tester, String name) async {
  if (!const bool.fromEnvironment('ALBUM_UI_PREVIEW')) return;
  await tester.runAsync(() async {
    final boundary = tester.firstRenderObject<RenderRepaintBoundary>(
      find.byKey(const ValueKey('preview')),
    );
    final image = await boundary.toImage();
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    await Directory('build/album-preview').create(recursive: true);
    await File(
      'build/album-preview/$name.png',
    ).writeAsBytes(data!.buffer.asUint8List());
    image.dispose();
  });
}

void main() {
  setUpAll(() async {
    const fonts = {
      'SF Pro': String.fromEnvironment('ALBUM_UI_FONT'),
      'MaterialIcons': String.fromEnvironment('ALBUM_ICON_FONT'),
    };
    for (final entry in fonts.entries) {
      if (entry.value.isEmpty) continue;
      final bytes = await File(entry.value).readAsBytes();
      await (FontLoader(
        entry.key,
      )..addFont(Future.value(ByteData.sublistView(bytes)))).load();
    }
  });
  test('PUT sends all nullable fields and DELETE accepts null data', () async {
    final requests = <RequestOptions>[];
    final dio = Dio();
    addTearDown(dio.close);
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (r, h) {
          requests.add(r);
          _resolve(r, h, r.method == 'DELETE' ? null : _album());
        },
      ),
    );
    final repo = AlbumRepository(
      AlbumRemoteDataSource(dio),
      () => throw UnimplementedError(),
    );
    final album = await repo.update(
      'album',
      name: '  新名称  ',
      description: '',
      coverFileId: null,
    );
    expect(album.name, '山野之间');
    expect(requests.single.method, 'PUT');
    expect(requests.single.path, '/albums/album');
    expect(requests.single.data, {
      'name': '新名称',
      'description': null,
      'coverFileId': null,
    });
    await repo.delete('album');
    expect(requests.last.method, 'DELETE');
    expect(requests.last.data, isNull);
    await expectLater(
      repo.update('album', name: ' ', description: null, coverFileId: null),
      throwsA(anything),
    );
    expect(requests.length, 2);
  });
  test(
    'edit retains existing cover, blocks duplicate saves and updates recents',
    () async {
      final pending = Completer<void>();
      final requests = <RequestOptions>[];
      final dio = Dio();
      addTearDown(dio.close);
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (r, h) async {
            requests.add(r);
            await pending.future;
            _resolve(r, h, {..._album(), 'name': '新名称'});
          },
        ),
      );
      final container = ProviderContainer(
        overrides: [
          dioProvider.overrideWithValue(dio),
          sessionProvider.overrideWith(_Session.new),
        ],
      );
      addTearDown(container.dispose);
      await container.read(sessionProvider.future);
      final provider = albumEditProvider(albumFromJson(_album()));
      final sub = container.listen(provider, (_, _) {});
      addTearDown(sub.close);
      final vm = container.read(provider.notifier);
      final saving = vm.create('新名称', '原介绍');
      expect(await vm.create('重复', ''), isNull);
      await Future<void>.delayed(Duration.zero);
      pending.complete();
      expect((await saving)?.name, '新名称');
      expect(requests.single.data, {
        'name': '新名称',
        'description': '原介绍',
        'coverFileId': 'cover',
      });
      expect(container.read(recentAlbumsProvider).single.name, '新名称');
    },
  );
  test(
    'delete failure keeps records and can retry; session change ignores completion',
    () async {
      final dio = Dio();
      addTearDown(dio.close);
      var count = 0;
      final pending = Completer<void>();
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (r, h) async {
            count++;
            if (count == 1) {
              h.reject(DioException(requestOptions: r));
              return;
            }
            await pending.future;
            _resolve(r, h, null);
          },
        ),
      );
      final container = ProviderContainer(
        overrides: [
          dioProvider.overrideWithValue(dio),
          sessionProvider.overrideWith(_Session.new),
        ],
      );
      addTearDown(container.dispose);
      await container.read(sessionProvider.future);
      final album = albumFromJson(_album());
      container.read(recentAlbumsProvider.notifier).remember(album);
      final provider = albumDeleteProvider(album.id);
      final sub = container.listen(provider, (_, _) {});
      addTearDown(sub.close);
      final vm = container.read(provider.notifier);
      expect(await vm.delete(), false);
      expect(container.read(recentAlbumsProvider), hasLength(1));
      expect(container.read(provider).error, isNotNull);
      final retry = vm.delete();
      expect(await vm.delete(), false);
      (container.read(sessionProvider.notifier) as _Session).change();
      await Future<void>.delayed(Duration.zero);
      pending.complete();
      expect(await retry, false);
      expect(container.read(recentAlbumsProvider), isEmpty);
    },
  );
  testWidgets(
    'edit prefills fields, clears cover, saves and returns to detail',
    (tester) async {
      tester.view.physicalSize = const Size(375, 812);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final dio = Dio();
      addTearDown(dio.close);
      var deleteRequests = 0;
      var json = _album();
      Map<String, dynamic>? payload;
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (r, h) {
            if (r.method == 'DELETE') deleteRequests++;
            if (r.method == 'PUT') {
              payload = r.data as Map<String, dynamic>;
              json = {...json, ...payload!};
            }
            _resolve(
              r,
              h,
              r.path.endsWith('/moments')
                  ? {'items': [], 'nextCursor': null, 'hasMore': false}
                  : json,
            );
          },
        ),
      );
      final container = ProviderContainer(
        overrides: [
          dioProvider.overrideWithValue(dio),
          sessionProvider.overrideWith(_Session.new),
        ],
      );
      addTearDown(container.dispose);
      await container.read(sessionProvider.future);
      final id = json['id'];
      final router = GoRouter(
        initialLocation: '/albums/$id',
        routes: [
          GoRoute(
            path: '/albums',
            builder: (_, _) => const Scaffold(body: Text('时光列表')),
          ),
          GoRoute(
            path: '/albums/:id',
            builder: (_, s) => AlbumDetailPage(id: s.pathParameters['id']!),
          ),
          GoRoute(
            path: '/albums/:id/edit',
            builder: (_, s) => AlbumEditPage(id: s.pathParameters['id']!),
          ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: RepaintBoundary(
            key: const ValueKey('preview'),
            child: MaterialApp.router(
              theme: AppTheme.light,
              routerConfig: router,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('设置封面'));
      await tester.pumpAndSettle();
      await _preview(tester, 'edit');
      expect(find.text('原介绍'), findsOneWidget);
      expect(find.textContaining('放入瞬间'), findsNothing);
      await tester.tap(find.text('移除封面'));
      await tester.enterText(find.byType(TextFormField).first, '新的时光');
      await tester.enterText(find.byType(TextFormField).last, '');
      await tester.tap(find.text('保存修改'));
      await tester.pumpAndSettle();
      expect(payload, {
        'name': '新的时光',
        'description': null,
        'coverFileId': null,
      });
      await _preview(tester, 'detail');
      expect(find.text('时光信息'), findsOneWidget);
      expect(find.text('新的时光'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.byTooltip('管理时光'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('删除时光'));
      await tester.pumpAndSettle();
      expect(find.text('删除后无法恢复，时光中的原瞬间会保留。'), findsOneWidget);
      await _preview(tester, 'delete');
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(deleteRequests, 0);
      await tester.tap(find.byTooltip('管理时光'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('删除时光'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(TextButton, '删除时光'));
      await tester.pumpAndSettle();
      expect(deleteRequests, 1);
      expect(find.text('时光列表'), findsOneWidget);
      expect(container.read(recentAlbumsProvider), isEmpty);
    },
  );
}
