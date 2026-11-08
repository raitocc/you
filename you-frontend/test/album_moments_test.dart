import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:you_app/app/theme/app_theme.dart';
import 'package:you_app/core/auth/session.dart';
import 'package:you_app/core/network/api_client.dart';
import 'package:you_app/features/album/data/album_inputs.dart';
import 'package:you_app/features/album/data/album_repository.dart';
import 'package:you_app/features/album/data/remote/album_remote_data_source.dart';
import 'package:you_app/features/album/presentation/viewmodels/album_moments_viewmodel.dart';
import 'package:you_app/features/album/presentation/viewmodels/album_viewmodels.dart';
import 'package:you_app/features/album/presentation/widgets/album_moments_sheet.dart';

class _Session extends SessionController {
  @override
  Future<String?> build() async => 'token';
  void change() => state = const AsyncData('other');
}

Map<String, dynamic> _album() => {
  'id': 'album',
  'name': '山野之间',
  'description': null,
  'coverFileId': null,
  'coverUrl': null,
  'organizeMode': 'manual',
  'momentCount': 2,
  'createdAt': '2026-11-08T00:00:00Z',
  'updatedAt': '2026-11-08T00:00:00Z',
};
void _resolve(RequestOptions r, RequestInterceptorHandler h, Object? data) =>
    h.resolve(
      Response(requestOptions: r, data: {'code': 'SUCCESS', 'data': data}),
    );
AlbumInputs _inputs() => AlbumInputs(
  moments: (cursor) async => (
    items: cursor == null
        ? [(id: 'm1', title: '山间散步', preview: null, coverUrl: null)]
        : [
            (id: 'm1', title: '山间散步', preview: null, coverUrl: null),
            (id: 'm2', title: '湖边日落', preview: null, coverUrl: null),
          ],
    cursor: cursor == null
        ? (memoryTime: '2026-11-08T00:00:00.123456789Z', id: 'm1')
        : null,
    hasMore: cursor == null,
  ),
  upload: (_, _) async => 'file',
  isReady: (_) async => true,
);
Future<void> _preview(WidgetTester tester, bool removing) async {
  if (!const bool.fromEnvironment('ALBUM_UI_PREVIEW')) return;
  await tester.runAsync(() async {
    final boundary = tester.firstRenderObject<RenderRepaintBoundary>(
      find.byKey(const ValueKey('batch-preview')),
    );
    final image = await boundary.toImage();
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    await Directory('build/album-preview').create(recursive: true);
    await File(
      'build/album-preview/batch-${removing ? 'remove' : 'add'}.png',
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
  test(
    'batch POST paths, deduplication, empty validation and result mapping',
    () async {
      final dio = Dio();
      addTearDown(dio.close);
      final requests = <RequestOptions>[];
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (r, h) {
            requests.add(r);
            _resolve(r, h, _album());
          },
        ),
      );
      final repo = AlbumRepository(AlbumRemoteDataSource(dio), _inputs);
      expect(
        (await repo.addMoments('album', ['m1', 'm1', 'm2'])).momentCount,
        2,
      );
      expect(requests.single.method, 'POST');
      expect(requests.single.path, '/albums/album/moments');
      expect(requests.single.data, {
        'momentIds': ['m1', 'm2'],
      });
      await repo.removeMoments('album', ['m2', 'm2']);
      expect(requests.last.method, 'POST');
      expect(requests.last.path, '/albums/album/moments/remove');
      expect(requests.last.data, {
        'momentIds': ['m2'],
      });
      await expectLater(repo.addMoments('album', []), throwsA(anything));
      await expectLater(repo.removeMoments('album', ['']), throwsA(anything));
      expect(requests, hasLength(2));
    },
  );
  test(
    'selection spans pages; failure retains selection, retry and duplicate submit guard',
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
            _resolve(r, h, _album());
          },
        ),
      );
      final container = ProviderContainer(
        overrides: [
          dioProvider.overrideWithValue(dio),
          sessionProvider.overrideWith(_Session.new),
          albumInputsProvider.overrideWithValue(_inputs()),
        ],
      );
      addTearDown(container.dispose);
      await container.read(sessionProvider.future);
      final provider = albumMomentsProvider((id: 'album', removing: false));
      final sub = container.listen(provider, (_, _) {});
      addTearDown(sub.close);
      final vm = container.read(provider.notifier);
      await vm.loadMore();
      vm.toggle('m1');
      await vm.loadMore();
      vm.toggle('m2');
      expect(container.read(provider).items, hasLength(2));
      expect(await vm.submit(), false);
      expect(container.read(provider).selected, {'m1', 'm2'});
      expect(container.read(provider).saveError, isNotNull);
      final retry = vm.submit();
      expect(await vm.submit(), false);
      pending.complete();
      expect(await retry, true);
      expect(count, 2);
      expect(container.read(recentAlbumsProvider).single.momentCount, 2);
    },
  );
  test(
    'removal loads album pages with all cursor fields and ignores stale completion',
    () async {
      final dio = Dio();
      addTearDown(dio.close);
      final requests = <RequestOptions>[];
      final pending = Completer<void>();
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (r, h) async {
            requests.add(r);
            if (r.method == 'POST') {
              await pending.future;
              _resolve(r, h, _album());
              return;
            }
            _resolve(r, h, {
              'items': [
                {
                  'id': requests.length == 1 ? 'm1' : 'm2',
                  'title': '记录',
                  'body': null,
                  'images': [],
                  'tags': [],
                  'mood': null,
                  'location': null,
                  'memoryTime': '2026-11-08T00:00:00Z',
                },
              ],
              'nextCursor': requests.length == 1
                  ? {
                      'memoryTime': '2026-11-08T00:00:00.123456789Z',
                      'createdAt': '2026-11-08T01:00:00.123456789Z',
                      'id': 'm1',
                    }
                  : null,
              'hasMore': requests.length == 1,
            });
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
      final provider = albumMomentsProvider((id: 'album', removing: true));
      final sub = container.listen(provider, (_, _) {});
      addTearDown(sub.close);
      final vm = container.read(provider.notifier);
      await vm.loadMore();
      vm.toggle('m1');
      await vm.loadMore();
      expect(requests[1].queryParameters, {
        'cursor.memoryTime': '2026-11-08T00:00:00.123456789Z',
        'cursor.createdAt': '2026-11-08T01:00:00.123456789Z',
        'cursor.id': 'm1',
      });
      final saving = vm.submit();
      await Future<void>.delayed(Duration.zero);
      (container.read(sessionProvider.notifier) as _Session).change();
      await Future<void>.delayed(Duration.zero);
      pending.complete();
      expect(await saving, false);
      expect(container.read(recentAlbumsProvider), isEmpty);
      expect(container.read(provider).selected, isEmpty);
    },
  );
  for (final removing in [false, true]) {
    testWidgets('batch sheet selection and submit removing=$removing', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(375, 812);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final dio = Dio();
      addTearDown(dio.close);
      RequestOptions? mutation;
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (r, h) {
            if (r.method == 'POST') {
              mutation = r;
              _resolve(r, h, _album());
              return;
            }
            _resolve(r, h, {
              'items': [
                {
                  'id': 'm1',
                  'title': '山间散步',
                  'body': null,
                  'images': [],
                  'tags': [],
                  'mood': null,
                  'location': null,
                  'memoryTime': '2026-11-08T00:00:00Z',
                },
              ],
              'nextCursor': null,
              'hasMore': false,
            });
          },
        ),
      );
      final container = ProviderContainer(
        overrides: [
          dioProvider.overrideWithValue(dio),
          sessionProvider.overrideWith(_Session.new),
          albumInputsProvider.overrideWithValue(_inputs()),
        ],
      );
      addTearDown(container.dispose);
      await container.read(sessionProvider.future);
      await tester.pumpWidget(
        RepaintBoundary(
          key: const ValueKey('batch-preview'),
          child: UncontrolledProviderScope(
            container: container,
            child: MaterialApp(
              theme: AppTheme.light,
              home: Scaffold(
                body: Builder(
                  builder: (context) => TextButton(
                    onPressed: () => showAlbumMomentsSheet(
                      context,
                      id: 'album',
                      removing: removing,
                    ),
                    child: const Text('管理瞬间'),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('管理瞬间'));
      await tester.pumpAndSettle();
      expect(
        tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNull,
      );
      await tester.tap(find.text('山间散步'));
      await tester.pumpAndSettle();
      await _preview(tester, removing);
      await tester.tap(find.text('${removing ? '移出' : '添加'}已选的 1 个瞬间'));
      await tester.pumpAndSettle();
      expect(mutation?.data, {
        'momentIds': ['m1'],
      });
      expect(find.byType(AlbumMomentsSheet), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }
}
