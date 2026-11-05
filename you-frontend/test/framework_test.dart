import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:you_app/app/app.dart';
import 'package:you_app/app/router/app_router.dart';
import 'package:you_app/core/auth/session.dart';

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

void main() {
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
  testWidgets('four tabs and root publish page', (tester) async {
    final container = ProviderContainer(
      overrides: [
        sessionProvider.overrideWith(() => FakeSession('test-token')),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const YouApp()),
    );
    await tester.pumpAndSettle();
    expect(find.text('与你有关'), findsOneWidget);
    await tester.tap(find.text('时光').last);
    await tester.pumpAndSettle();
    expect(
      container.read(routerProvider).routeInformationProvider.value.uri.path,
      '/albums',
    );
    await tester.tap(find.byTooltip('发布瞬间'));
    await tester.pumpAndSettle();
    expect(find.text('发布瞬间'), findsOneWidget);
    expect(find.byTooltip('个人资料'), findsNothing);
    container.read(routerProvider).pop();
    await tester.pumpAndSettle();
    expect(find.byTooltip('个人资料'), findsOneWidget);
  });
}
