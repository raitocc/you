import '../../features/user/presentation/pages/register_page.dart';
import '../../features/user/presentation/pages/profile_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/session.dart';
import '../../shared/ui/page_state.dart';
import '../../features/user/presentation/pages/login_page.dart';
import '../../features/moment/presentation/pages/moment_page.dart';
import '../../features/album/presentation/pages/album_page.dart';
import '../../features/gathering/presentation/pages/gathering_page.dart';
import '../../features/circle/presentation/pages/circle_page.dart';
import '../shell/app_shell.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final refresh = ValueNotifier(0);
  ref.listen(sessionProvider, (_, _) => refresh.value++);
  final router = GoRouter(
    initialLocation: '/moments',
    refreshListenable: refresh,
    redirect: (context, state) {
      final session = ref.read(sessionProvider);
      if (session.isLoading) {
        return state.matchedLocation == '/startup'
            ? null
            : '/startup?from=${Uri.encodeComponent(state.uri.toString())}';
      }
      final loggedIn = session.asData?.value != null;
      final isEntry =
          state.matchedLocation == '/login' ||
          state.matchedLocation == '/register' ||
          state.matchedLocation == '/startup';
      if (!loggedIn && !isEntry) {
        return '/login?from=${Uri.encodeComponent(state.uri.toString())}';
      }
      if (state.matchedLocation == '/startup' || (loggedIn && isEntry)) {
        final from = state.uri.queryParameters['from'];
        final safe =
            from != null &&
            from.startsWith('/') &&
            !from.startsWith('//') &&
            !from.startsWith('/login') &&
            !from.startsWith('/register') &&
            !from.startsWith('/startup');
        return loggedIn
            ? (safe ? from : '/moments')
            : '/login?from=${Uri.encodeComponent(from ?? '/moments')}';
      }
      return null;
    },
    routes: [
      GoRoute(
        path: '/startup',
        builder: (_, _) => const Scaffold(body: LoadingState()),
      ),
      GoRoute(path: '/login', builder: (_, _) => const LoginPage()),
      GoRoute(path: '/register', builder: (_, _) => const RegisterPage()),
      GoRoute(path: '/profile', builder: (_, _) => const ProfilePage()),
      StatefulShellRoute.indexedStack(
        builder: (_, _, shell) => AppShell(navigationShell: shell),
        branches: [
          StatefulShellBranch(
            routes: [
              GoRoute(path: '/moments', builder: (_, _) => const MomentPage()),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(path: '/albums', builder: (_, _) => const AlbumPage()),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/gatherings',
                builder: (_, _) => const GatheringPage(),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(path: '/circles', builder: (_, _) => const CirclePage()),
            ],
          ),
        ],
      ),
      for (final path in [
        '/moments/new',
        '/moments/:id',
        '/moments/:id/edit',
        '/albums/new',
        '/albums/:id',
        '/gatherings/new',
        '/gatherings/:id',
        '/circles/new',
        '/circles/:id',
        '/search',
        '/messages',
        '/ai/tasks',
        '/settings',
        '/settings/privacy',
        '/settings/ai',
        '/settings/data',
      ])
        GoRoute(
          path: path,
          builder: (context, state) => Scaffold(
            appBar: AppBar(
              title: Text(path == '/moments/new' ? '发布瞬间' : 'YOU'),
            ),
            body: const PageState(title: '即将相遇', message: '页面框架已就绪，业务功能待接入'),
          ),
        ),
    ],
    errorBuilder: (context, state) => Scaffold(
      appBar: AppBar(title: const Text('页面未找到')),
      body: PageState(
        title: '页面不存在',
        message: '请返回首页',
        onRetry: () => context.go('/moments'),
      ),
    ),
  );
  ref.onDispose(() {
    router.dispose();
    refresh.dispose();
  });
  return router;
});
