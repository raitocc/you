import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/network/api_client.dart';
import '../../../../shared/models/cursor_page.dart';
import '../../../../shared/ui/page_state.dart';
import '../../models/gathering.dart';
import '../viewmodels/gathering_members_viewmodel.dart';

/// 成员列表按关系顺序分页，头像与昵称来自公开资料。
class GatheringMembersPage extends ConsumerWidget {
  const GatheringMembersPage({super.key, required this.id});
  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final value = ref.watch(gatheringMembersProvider(id));
    final vm = ref.read(gatheringMembersProvider(id).notifier);
    return Scaffold(
      appBar: AppBar(title: const Text('同行成员')),
      body: value.when(
        loading: () => const LoadingState(),
        error: (error, _) => PageState(
          title: '暂时无法查看成员',
          message: errorMessage(error),
          onRetry: vm.refresh,
        ),
        data: (data) => RefreshIndicator(
          onRefresh: vm.refresh,
          child: _MemberList(id: id, data: data),
        ),
      ),
    );
  }
}

class _MemberList extends ConsumerWidget {
  const _MemberList({required this.id, required this.data});
  final String id;
  final BrowseState<GatheringMember, GatheringMemberCursor> data;

  @override
  Widget build(BuildContext context, WidgetRef ref) => ListView.builder(
    physics: const AlwaysScrollableScrollPhysics(),
    padding: const EdgeInsets.symmetric(vertical: 12),
    itemCount: data.items.length + 1,
    itemBuilder: (context, index) {
      if (index == data.items.length) return _footer(ref);
      final member = data.items[index];
      return _MemberTile(key: ValueKey(member.listKey), user: member.user);
    },
  );

  Widget _footer(WidgetRef ref) {
    if (data.items.isEmpty) {
      return const Padding(padding: EdgeInsets.all(32), child: Text('暂无成员'));
    }
    if (data.loadingMore) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (!data.hasMore) {
      return const Padding(
        padding: EdgeInsets.all(24),
        child: Center(child: Text('已展示全部成员')),
      );
    }
    return Column(
      children: [
        if (data.error != null) Text(errorMessage(data.error)),
        TextButton(
          onPressed: ref.read(gatheringMembersProvider(id).notifier).loadMore,
          child: Text(data.error == null ? '加载更多' : '重试加载'),
        ),
      ],
    );
  }
}

class _MemberTile extends StatelessWidget {
  const _MemberTile({super.key, required this.user});
  final GatheringPerson? user;

  @override
  Widget build(BuildContext context) {
    final url = user?.avatarUrl;
    return ListTile(
      leading: CircleAvatar(
        child: url == null
            ? const Icon(Icons.person_outline)
            : ClipOval(
                child: Image.network(
                  url,
                  width: 40,
                  height: 40,
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => const Icon(Icons.person_outline),
                ),
              ),
      ),
      title: Text(user?.nickname ?? '用户资料暂无'),
    );
  }
}
