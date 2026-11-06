import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:you_app/core/auth/session.dart';
import 'package:you_app/core/network/api_client.dart';
import 'package:you_app/features/user/data/remote/user_remote_data_source.dart';
import 'package:you_app/features/user/data/user_repository.dart';
import 'package:you_app/features/user/models/profile_edits.dart';
import 'package:you_app/features/user/presentation/viewmodels/profile_viewmodel.dart';

class _Session extends SessionController {
  @override
  Future<String?> build() async => 'test-token';
}

class _UserRepository extends UserRepository {
  _UserRepository(super.remote);

  @override
  Future<ProfileEdits> profileEdits(String userId) async =>
      (nickname: null, signature: '', avatar: null, background: null);
}

void main() {
  test(
    'avatar uploads, binds after registration and reloads its read URL',
    () async {
      const uploadUrl = 'https://storage.example/image?signature=put';
      const firstReadUrl = 'https://storage.example/image?signature=old';
      const freshReadUrl = 'https://storage.example/image?signature=new';
      final image = Uint8List.fromList([1, 2, 3]);
      final requests = <String>[];
      var bound = false;
      var checks = 0;

      Map<String, dynamic> profile(String? url) => {
        'id': 'user-id',
        'username': 'user',
        'nickname': 'User',
        'avatarFileId': bound ? 'file-id' : null,
        'avatarUrl': url,
      };

      final container = ProviderContainer(
        overrides: [
          sessionProvider.overrideWith(_Session.new),
          userRepositoryProvider.overrideWith(
            (ref) => _UserRepository(ref.watch(userRemoteDataSourceProvider)),
          ),
        ],
      );
      addTearDown(container.dispose);
      await container.read(sessionProvider.future);
      container
          .read(dioProvider)
          .interceptors
          .add(
            InterceptorsWrapper(
              onRequest: (options, handler) {
                final request = '${options.method} ${options.path}';
                requests.add(request);
                final data = switch (request) {
                  'GET /users/me' => profile(bound ? freshReadUrl : null),
                  'POST /media/uploads' => {
                    'fileId': 'file-id',
                    'url': uploadUrl,
                  },
                  'GET /media/uploads/file-id' => {'ready': ++checks == 2},
                  'PUT /users/me' => profile(firstReadUrl),
                  _ => throw StateError(request),
                };
                if (request == 'PUT /users/me') {
                  expect(checks, 2);
                  expect(options.data, {
                    'nickname': 'User',
                    'avatarFileId': 'file-id',
                  });
                  bound = true;
                  data['avatarFileId'] = 'file-id';
                }
                handler.resolve(
                  Response(
                    requestOptions: options,
                    data: {'code': 'SUCCESS', 'data': data},
                  ),
                );
              },
            ),
          );
      container
          .read(storageDioProvider)
          .interceptors
          .add(
            InterceptorsWrapper(
              onRequest: (options, handler) {
                final url = options.uri.toString();
                requests.add('${options.method} $url');
                expect(options.headers, isNot(contains('Authorization')));
                if (url == firstReadUrl) {
                  handler.reject(
                    DioException.badResponse(
                      statusCode: 403,
                      requestOptions: options,
                      response: Response(
                        requestOptions: options,
                        statusCode: 403,
                      ),
                    ),
                  );
                  return;
                }
                handler.resolve(
                  Response(
                    requestOptions: options,
                    data: url == freshReadUrl ? image : null,
                  ),
                );
              },
            ),
          );

      final subscription = container.listen(profileAvatarProvider, (_, _) {});
      addTearDown(subscription.close);
      await container.read(profileProvider.future);
      await container
          .read(profileProvider.notifier)
          .save(avatar: (bytes: image, mimeType: 'image/png'));
      expect(await container.read(profileAvatarProvider.future), image);
      expect(requests, [
        'GET /users/me',
        'POST /media/uploads',
        'PUT $uploadUrl',
        'GET /media/uploads/file-id',
        'GET /media/uploads/file-id',
        'PUT /users/me',
        'GET $firstReadUrl',
        'GET /users/me',
        'GET $freshReadUrl',
      ]);
    },
  );
}
