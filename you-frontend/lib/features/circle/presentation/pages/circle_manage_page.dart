import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/ui/page_state.dart';
import '../../models/circle.dart';
import '../viewmodels/circle_viewmodels.dart';
import '../widgets/circle_widgets.dart';
import 'circle_editor_page.dart';

class CircleManagePage extends ConsumerStatefulWidget {
  const CircleManagePage({super.key, required this.id});
  final String id;
  @override
  ConsumerState<CircleManagePage> createState() => _CircleManagePageState();
}

class _CircleManagePageState extends ConsumerState<CircleManagePage> {
  bool busy = false;
  CircleActions get actions => ref.read(circleActionsProvider);

  Future<void> run(Future<void> Function() work) async {
    if (busy) return;
    setState(() => busy = true);
    try {
      await work();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(errorMessage(e))));
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final id = widget.id;
    final provider = circleDetailsProvider(id);
    return Scaffold(
      appBar: AppBar(title: const Text('圈子详情')),
      body: ref
          .watch(provider)
          .when(
            loading: () => const LoadingState(),
            error: (error, _) => PageState(
              title: '无法查看圈子',
              message: errorMessage(error),
              onRetry: () => ref.invalidate(provider),
            ),
            data: (details) => ListView(
              padding: const EdgeInsets.all(20),
              children: [
                Center(
                  child: CircleProfileAvatar(
                    details.circle.avatarUrl,
                    radius: 44,
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  details.circle.name,
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                if (details.description case final description?)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(description, textAlign: TextAlign.center),
                  ),
                if (details.isCreator)
                  Center(
                    child: OutlinedButton.icon(
                      onPressed: busy
                          ? null
                          : () => showCircleEditor(context, details: details),
                      icon: const Icon(Icons.edit_outlined),
                      label: const Text('编辑资料与头像'),
                    ),
                  ),
                const SizedBox(height: 20),
                Text(
                  '圈子成员 · ${details.members.length} 人',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                for (final member in details.members)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: CircleProfileAvatar(
                      member.user.avatarUrl,
                      radius: 18,
                    ),
                    title: Text(member.user.name),
                    subtitle: member.isCreator ? const Text('创建者') : null,
                    trailing: details.isCreator && !member.isCreator
                        ? TextButton(
                            onPressed: busy
                                ? null
                                : () => run(() async {
                                    if (await confirmCircleAction(
                                          context,
                                          '移除 ${member.user.name}？',
                                          '该成员的圈内分享会撤回，并限制再次加入。',
                                        ) &&
                                        mounted) {
                                      await actions.removeMember(
                                        id,
                                        member.user.id,
                                      );
                                    }
                                  }),
                            child: const Text('移除'),
                          )
                        : null,
                  ),
                const Divider(),
                _invitation(id, details.isCreator),
                if (details.isCreator)
                  ExpansionTile(
                    title: const Text('加入限制'),
                    children: [_restrictions(id)],
                  ),
                ListTile(
                  title: Text(
                    details.isCreator ? '解散圈子' : '退出圈子',
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                  onTap: busy
                      ? null
                      : () => run(() async {
                          final dissolve = details.isCreator;
                          if (!await confirmCircleAction(
                                context,
                                dissolve ? '解散这个圈子？' : '退出这个圈子？',
                                dissolve
                                    ? '邀请、成员关系与圈内分享将失效。'
                                    : '退出后将无法查看圈内分享，自己的瞬间会保留。',
                              ) ||
                              !mounted) {
                            return;
                          }
                          await actions.exit(id, dissolve: dissolve);
                          if (context.mounted) context.go('/circles');
                        }),
                ),
                if (busy) const LinearProgressIndicator(),
              ],
            ),
          ),
    );
  }

  Widget _invitation(String id, bool creator) {
    final provider = circleInvitationProvider(id);
    return ref
        .watch(provider)
        .when(
          loading: () => const LinearProgressIndicator(),
          error: (error, _) => ListTile(
            title: Text(errorMessage(error)),
            trailing: TextButton(
              onPressed: () => ref.invalidate(provider),
              child: const Text('重试'),
            ),
          ),
          data: (invite) => Column(
            children: [
              ListTile(
                title: Text(
                  invite.code == null ? '圈子邀请' : '邀请码 ${invite.code}',
                ),
                subtitle: Text(invite.enabled ? '邀请已开启' : '邀请已关闭'),
                trailing: invite.code == null || !invite.enabled
                    ? null
                    : IconButton(
                        tooltip: '复制邀请码',
                        icon: const Icon(Icons.copy),
                        onPressed: busy
                            ? null
                            : () => run(() async {
                                await Clipboard.setData(
                                  ClipboardData(text: invite.code.toString()),
                                );
                                if (mounted) {
                                  ScaffoldMessenger.of(context).showSnackBar(
                                    const SnackBar(content: Text('邀请码已复制')),
                                  );
                                }
                              }),
                      ),
              ),
              if (creator) ...[
                SwitchListTile(
                  title: const Text('允许凭邀请码加入'),
                  value: invite.enabled,
                  onChanged: busy
                      ? null
                      : (value) =>
                            run(() => actions.updateInvitation(id, value)),
                ),
                ListTile(
                  title: const Text('重置邀请码'),
                  onTap: busy
                      ? null
                      : () => run(() async {
                          if (await confirmCircleAction(
                                context,
                                '重置邀请码？',
                                '原邀请码将失效。',
                              ) &&
                              mounted) {
                            await actions.updateInvitation(id, null);
                          }
                        }),
                ),
              ],
            ],
          ),
        );
  }

  Widget _restrictions(String id) {
    final provider = circleRestrictionsProvider(id);
    return ref
        .watch(provider)
        .when(
          loading: () => const LinearProgressIndicator(),
          error: (error, _) => ListTile(
            title: Text(errorMessage(error)),
            trailing: TextButton(
              onPressed: () => ref.invalidate(provider),
              child: const Text('重试'),
            ),
          ),
          data: (people) => people.isEmpty
              ? const ListTile(title: Text('暂无受限制的用户'))
              : Column(
                  children: [
                    for (final person in people) _restricted(id, person),
                  ],
                ),
        );
  }

  Widget _restricted(String id, CirclePerson person) => ListTile(
    leading: CircleProfileAvatar(person.avatarUrl, radius: 18),
    title: Text(person.name),
    trailing: TextButton(
      onPressed: busy
          ? null
          : () => run(() => actions.liftRestriction(id, person.id)),
      child: const Text('解除限制'),
    ),
  );
}
