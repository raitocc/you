import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../shared/models/cursor_page.dart';
import '../../../../shared/ui/paged_browse.dart';
import '../../data/gathering_repository.dart';
import '../../models/gathering.dart';

final gatheringMembersProvider = AsyncNotifierProvider.autoDispose
    .family<
      GatheringMembersViewModel,
      BrowseState<GatheringMember, GatheringMemberCursor>,
      String
    >(GatheringMembersViewModel.new);

/// 各同行分别保存成员页，沿用公共分页的去重、失败重试与账号隔离。
class GatheringMembersViewModel
    extends PagedBrowse<GatheringMember, GatheringMemberCursor> {
  GatheringMembersViewModel(this.id);
  final String id;

  @override
  Future<GatheringMemberPageData> fetch(GatheringMemberCursor? cursor) =>
      ref.read(gatheringRepositoryProvider).members(id, cursor: cursor);

  @override
  String key(GatheringMember item) => item.listKey;

  /// 刷新首屏，错误由页面中的 AsyncError 展示。
  Future<void> refresh() async {
    ref.invalidateSelf();
    try {
      await future;
    } catch (_) {
      // 页面展示错误与重试入口，此处结束下拉刷新动画。
    }
  }
}
