import 'package:flutter/material.dart';

import '../../models/gathering.dart';
import 'gathering_card.dart';

/// 先展示目标供用户核对，确认按钮根据成员身份切换为直接进入。
class GatheringJoinPreviewCard extends StatelessWidget {
  const GatheringJoinPreviewCard({
    super.key,
    required this.preview,
    required this.busy,
    required this.onConfirm,
  });

  final GatheringJoinPreview preview;
  final bool busy;
  final VoidCallback onConfirm;

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(preview.name, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 16),
          GatheringCreator(creator: preview.creator),
          const SizedBox(height: 16),
          Text(
            preview.description?.isNotEmpty == true
                ? preview.description!
                : '还没有填写介绍',
          ),
          const SizedBox(height: 20),
          if (preview.isMember) ...[
            const Text('你已是这个同行的成员'),
            const SizedBox(height: 12),
          ],
          FilledButton(
            onPressed: busy ? null : onConfirm,
            child: Text(
              busy
                  ? '加入中…'
                  : preview.isMember
                  ? '进入同行'
                  : '确认加入',
            ),
          ),
        ],
      ),
    ),
  );
}
