import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../models/user_profile.dart';
import '../viewmodels/profile_viewmodel.dart';

class ProfilePage extends ConsumerWidget {
  const ProfilePage({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
    appBar: AppBar(title: const Text('个人资料')),
    body: ref
        .watch(profileProvider)
        .when(
          data: (profile) =>
              _ProfileDetails(key: ValueKey(profile.id), profile: profile),
          loading: () => const LoadingState(),
          error: (error, _) => PageState(
            title: '资料加载失败',
            message: errorMessage(error),
            onRetry: () => ref.invalidate(profileProvider),
          ),
        ),
  );
}

class _ProfileDetails extends ConsumerStatefulWidget {
  const _ProfileDetails({super.key, required this.profile});
  final UserProfile profile;
  @override
  ConsumerState<_ProfileDetails> createState() => _ProfileDetailsState();
}

class _ProfileDetailsState extends ConsumerState<_ProfileDetails> {
  bool busy = false;
  String? error;
  Future<void> action(Future<void> Function() work) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await work();
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('操作成功')));
      }
    } catch (e) {
      if (mounted) setState(() => error = errorMessage(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(24),
    children: [
      const Center(
        child: CircleAvatar(
          radius: 44,
          child: Icon(Icons.person_outline, size: 40),
        ),
      ),
      const SizedBox(height: 16),
      Text('账号  ${widget.profile.username}'),
      const SizedBox(height: 16),
      Text('昵称  ${widget.profile.nickname}'),
      const SizedBox(height: 24),
      if (error != null) Text(error!),
      OutlinedButton(
        onPressed: busy
            ? null
            : () => action(() => ref.read(profileProvider.notifier).logout()),
        child: Text(busy ? '正在退出…' : '退出登录'),
      ),
    ],
  );
}
