import 'package:flutter/material.dart';
import '../../../../core/network/api_client.dart';
import '../../../../shared/models/cursor_page.dart';
import '../../../moment/models/moment.dart';
import '../../models/gathering_moment.dart';

/// 时间线与选择页共用分页状态提示，重试保持原游标和已有内容。
class GatheringMomentsFooter extends StatelessWidget {
  const GatheringMomentsFooter({
    super.key,
    required this.data,
    required this.onLoadMore,
    this.enabled = true,
  });
  final BrowseState<GatheringMomentSummary, MomentFeedCursor> data;
  final VoidCallback onLoadMore;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    if (data.loadingMore) {
      return const Padding(
        padding: EdgeInsets.all(20),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    if (data.items.isEmpty) {
      return const Padding(
        padding: EdgeInsets.all(28),
        child: Center(child: Text('还没有瞬间')),
      );
    }
    if (!data.hasMore) {
      return const Padding(
        padding: EdgeInsets.all(20),
        child: Center(child: Text('已展示全部瞬间')),
      );
    }
    return Column(
      children: [
        if (data.error != null) Text(errorMessage(data.error)),
        TextButton(
          onPressed: enabled ? onLoadMore : null,
          child: Text(data.error == null ? '加载更多瞬间' : '重试加载瞬间'),
        ),
      ],
    );
  }
}
