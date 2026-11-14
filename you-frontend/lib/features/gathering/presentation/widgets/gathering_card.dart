import 'package:flutter/material.dart';

import '../../../../app/theme/app_theme.dart';
import '../../models/gathering.dart';

/// 同行摘要卡片，按列表模型展示，点击动作交给页面。
class GatheringCard extends StatelessWidget {
  const GatheringCard({
    super.key,
    required this.gathering,
    required this.onTap,
  });

  final GatheringSummary gathering;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final preview = gathering.descriptionPreview;
    return Card(
      color: Tokens.green.withValues(alpha: 0.45),
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(22),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.people_outline, color: Tokens.accent),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      gathering.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                  ),
                  const Icon(Icons.chevron_right, color: Tokens.muted),
                ],
              ),
              Text(gathering.status.label),
              if (preview != null && preview.isNotEmpty) ...[
                const SizedBox(height: 12),
                Text(preview, maxLines: 3, overflow: TextOverflow.ellipsis),
              ],
              const SizedBox(height: 20),
              GatheringCounts(
                memberCount: gathering.memberCount,
                momentCount: gathering.momentCount,
              ),
              const SizedBox(height: 16),
              GatheringCreator(creator: gathering.creator),
            ],
          ),
        ),
      ),
    );
  }
}

/// 列表与详情共用的有效成员和瞬间数量。
class GatheringCounts extends StatelessWidget {
  const GatheringCounts({
    super.key,
    required this.memberCount,
    required this.momentCount,
  });

  final int memberCount;
  final int momentCount;

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: 20,
    runSpacing: 8,
    children: [Text('$memberCount 位同行成员'), Text('$momentCount 个瞬间')],
  );
}

/// 创建者资料缺失时展示文字提示，头像缺失或读取失败时展示通用图标。
class GatheringCreator extends StatelessWidget {
  const GatheringCreator({super.key, required this.creator});

  final GatheringPerson? creator;

  @override
  Widget build(BuildContext context) {
    final person = creator;
    final avatar = person?.avatarUrl;
    return Row(
      children: [
        ClipOval(
          child: avatar == null
              ? _placeholder()
              : Image.network(
                  avatar,
                  width: 30,
                  height: 30,
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => _placeholder(),
                ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Text(
            person == null ? '创建者资料暂无' : '${person.nickname} 发起',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: Tokens.muted),
          ),
        ),
      ],
    );
  }

  Widget _placeholder() => const SizedBox(
    width: 30,
    height: 30,
    child: ColoredBox(
      color: Tokens.green,
      child: Icon(Icons.person_outline, size: 20, color: Tokens.accent),
    ),
  );
}
