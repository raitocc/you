import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:you_app/core/auth/session.dart';
import 'package:you_app/core/network/api_client.dart';
import 'package:you_app/core/errors/app_exception.dart';
import 'package:you_app/features/user/data/user_repository.dart';
import 'package:you_app/features/media/data/media_repository.dart';

class MemorySession extends SessionController {
  @override
  Future<String?> build() async => null;
  @override
  Future<void> setToken(String token) async {
    state = AsyncData(token);
  }

  @override
  Future<void> clear() async {
    state = const AsyncData(null);
  }
}

void main() {
  test(
    'live user and media repositories match the live API contract',
    () async {
      final container = ProviderContainer(
        overrides: [sessionProvider.overrideWith(MemorySession.new)],
      );
      addTearDown(container.dispose);
      await container.read(sessionProvider.future);
      final users = container.read(userRepositoryProvider);
      final account = 'flutter_it_${DateTime.now().microsecondsSinceEpoch}';
      // Only IDs created by this run are recorded for explicit database cleanup.
      final created = <String>[];
      final cleanup = File('build/api-live-users.json');
      expect(
        cleanup.existsSync(),
        isFalse,
        reason: '先运行 tool/cleanup_api_fixtures.py 清理上次测试数据',
      );
      Future<void> record(String id) async {
        created.add(id);
        await cleanup.writeAsString(jsonEncode(created));
      }

      const password = ' Frontend12345 ';
      final me = await users.register(account, password, ' 连调用户 ');
      await record(me.id);
      expect(me.nickname, '连调用户');
      await expectLater(
        users.register(account, password, '重复'),
        throwsA(
          isA<DioException>().having(
            (e) => e.response?.statusCode,
            'status',
            409,
          ),
        ),
      );
      await expectLater(
        users.login(account, 'wrong123'),
        throwsA(
          isA<DioException>().having(
            (e) => e.error,
            'error',
            isA<AppException>(),
          ),
        ),
      );
      final token = await users.login(account.toUpperCase(), password);
      await container.read(sessionProvider.notifier).setToken(token);
      expect((await users.current()).id, me.id);
      final image = await File('test/fixtures/avatar.png').readAsBytes();
      var putRequested = false;
      container
          .read(storageDioProvider)
          .interceptors
          .add(
            InterceptorsWrapper(
              onRequest: (options, handler) {
                // Use the real presign endpoint without leaving an object in storage.
                putRequested = true;
                expect(options.method, 'PUT');
                expect(
                  options.uri.queryParameters,
                  contains('X-Amz-Signature'),
                );
                expect(options.contentType, 'image/png');
                expect(
                  options.headers[Headers.contentLengthHeader],
                  image.length,
                );
                expect(
                  options.headers.keys.map((e) => e.toLowerCase()),
                  isNot(contains('authorization')),
                );
                handler.resolve(
                  Response<void>(requestOptions: options, statusCode: 200),
                );
              },
            ),
          );
      await container
          .read(mediaRepositoryProvider)
          .upload(Uint8List.fromList(image), 'image/png');
      expect(putRequested, isTrue);
      await container.read(sessionProvider.notifier).clear();
      await expectLater(
        users.current(),
        throwsA(
          isA<DioException>().having(
            (e) => e.response?.statusCode,
            'status',
            401,
          ),
        ),
      );
      await container.read(sessionProvider.notifier).setToken('invalid-token');
      await expectLater(users.current(), throwsA(isA<DioException>()));
      expect(container.read(sessionProvider).asData?.value, isNull);
    },
    skip: !const bool.fromEnvironment('RUN_API_TESTS'),
    timeout: const Timeout(Duration(minutes: 2)),
  );
}
