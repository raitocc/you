import '../../features/user/presentation/pages/register_page.dart';
import '../../features/user/presentation/pages/profile_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/session.dart';
import '../../shared/ui/page_state.dart';
import '../../features/user/presentation/pages/login_page.dart';
import '../../features/moment/presentation/pages/moment_page.dart';
import '../../features/moment/presentation/pages/moment_detail_page.dart';
import '../../features/moment/presentation/pages/moment_editor_page.dart';
import '../../features/album/presentation/pages/album_page.dart';
import '../../features/album/presentation/pages/album_editor_page.dart';
import '../../features/album/presentation/pages/album_detail_page.dart';
import '../../features/gathering/presentation/pages/gathering_page.dart';
import '../../features/gathering/presentation/pages/gathering_face_join_page.dart';
import '../../features/gathering/presentation/pages/gathering_face_page.dart';
import '../../features/gathering/presentation/pages/gathering_face_create_page.dart';
import '../../features/gathering/presentation/pages/gathering_moments_page.dart';
import '../../features/gathering/presentation/pages/gathering_moment_detail_page.dart';
import '../../features/gathering/presentation/pages/gathering_moment_selection_page.dart';
import '../../features/gathering/presentation/pages/gathering_picker_page.dart';
import '../../features/gathering/presentation/pages/gathering_invite_page.dart';
import '../../features/gathering/presentation/pages/gathering_join_page.dart';
import '../../features/gathering/presentation/pages/gathering_scan_page.dart';
import '../../features/gathering/presentation/pages/gathering_create_page.dart';
import '../../features/gathering/presentation/pages/gathering_detail_page.dart';
import '../../features/gathering/presentation/pages/gathering_edit_page.dart';
import '../../features/gathering/presentation/pages/gathering_members_page.dart';
import '../../features/circle/presentation/pages/circle_page.dart';
import '../../features/circle/presentation/pages/circle_editor_page.dart';
import '../../features/circle/presentation/pages/circle_manage_page.dart';
import '../../features/circle/presentation/pages/circle_share_page.dart';
import '../../features/circle/models/circle.dart';
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
      GoRoute(
        path: '/profile',
        pageBuilder: (context, state) => CustomTransitionPage<void>(
          key: state.pageKey,
          opaque: false,
          barrierColor: Colors.black54,
          barrierDismissible: true,
          barrierLabel: MaterialLocalizations.of(
            context,
          ).modalBarrierDismissLabel,
          child: const ProfilePage(),
          transitionsBuilder: (_, animation, _, child) => SlideTransition(
            position: animation.drive(
              Tween(
                begin: const Offset(0, 1),
                end: Offset.zero,
              ).chain(CurveTween(curve: Curves.easeOutCubic)),
            ),
            child: child,
          ),
        ),
      ),
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
              GoRoute(
                path: '/circles',
                builder: (_, state) {
                  final selected = state.uri.queryParameters['selected'];
                  return CirclePage(
                    key: ValueKey(selected),
                    initialCircle: selected,
                  );
                },
              ),
            ],
          ),
        ],
      ),
      GoRoute(
        path: '/moments/new',
        builder: (_, _) => const MomentEditorPage(),
      ),
      GoRoute(
        path: '/moments/:id/edit',
        builder: (_, state) => MomentEditorPage(
          id: state.pathParameters['id']!,
          focusSharing: state.uri.queryParameters['focus'] == 'sharing',
        ),
      ),
      GoRoute(
        path: '/moments/:id',
        builder: (_, state) =>
            MomentDetailPage(id: state.pathParameters['id']!),
      ),
      GoRoute(
        path: '/albums/:id/edit',
        builder: (_, state) => AlbumEditPage(id: state.pathParameters['id']!),
      ),
      GoRoute(path: '/albums/new', builder: (_, _) => const AlbumEditorPage()),
      GoRoute(
        path: '/albums/:id',
        builder: (_, state) => AlbumDetailPage(id: state.pathParameters['id']!),
      ),
      GoRoute(path: '/circles/new', builder: (_, _) => const CircleNewPage()),
      GoRoute(path: '/circles/join', builder: (_, _) => const CircleJoinPage()),
      for (final type in CircleShareType.values)
        GoRoute(
          path: '/circles/share/${type.name}/:id',
          builder: (_, state) =>
              CircleSharePage(type: type, id: state.pathParameters['id']!),
        ),
      GoRoute(
        path: '/circles/:id/shares/:shareId',
        builder: (_, state) => SharedTimelinePage(
          share: (
            circleId: state.pathParameters['id']!,
            shareId: state.pathParameters['shareId']!,
          ),
          title: state.extra is String ? state.extra as String : null,
        ),
      ),
      GoRoute(
        path: '/circles/:id',
        builder: (_, state) =>
            CircleManagePage(id: state.pathParameters['id']!),
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
        path: '/gatherings/join',
        builder: (_, _) => const GatheringJoinPage(),
      ),
      GoRoute(
        path: '/gatherings/scan',
        builder: (_, _) => const GatheringScanPage(),
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
        path: '/gatherings/:id/invite',
        builder: (_, state) =>
            GatheringInvitePage(id: state.pathParameters['id']!),
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
      for (final path in [
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
            appBar: AppBar(title: const Text('YOU')),
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
