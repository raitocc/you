import '../../../shared/models/cursor_page.dart';

/// 状态与后端枚举同名，列表与详情共用。
enum GatheringStatus {
  ongoing('ONGOING', '进行中'),
  ended('ENDED', '已结束');

  const GatheringStatus(this.value, this.label);
  final String value;
  final String label;

  static GatheringStatus fromJson(String value) =>
      values.firstWhere((status) => status.value == value);
}

/// 同行中的用户公开资料，资料缺失时由上层保留 null。
typedef GatheringPerson = ({String id, String nickname, String? avatarUrl});

/// 同行列表摘要；介绍由服务端截取，列表沿用服务端排序。
class GatheringSummary {
  const GatheringSummary({
    required this.status,
    required this.id,
    required this.name,
    required this.descriptionPreview,
    required this.creator,
    required this.memberCount,
    required this.momentCount,
    required this.createdAt,
  });

  final GatheringStatus status;
  final String id;
  final String name;
  final String? descriptionPreview;
  final GatheringPerson? creator;
  final int memberCount;
  final int momentCount;
  final DateTime createdAt;
}

/// 同行基本信息，成员与瞬间通过各自的分页接口接入。
class Gathering {
  const Gathering({
    required this.status,
    required this.id,
    required this.name,
    required this.description,
    required this.creator,
    required this.memberCount,
    required this.momentCount,
    required this.inviteCode,
    required this.createdAt,
    required this.updatedAt,
  });

  final GatheringStatus status;
  final String id;
  final String name;
  final String? description;
  final GatheringPerson? creator;
  final int memberCount;
  final int momentCount;
  final String inviteCode;
  final DateTime createdAt;
  final DateTime updatedAt;
}

/// 时间原样回传，保留服务端游标精度。
typedef GatheringCursor = ({String createdAt, String id});
typedef GatheringPageData = BrowsePage<GatheringSummary, GatheringCursor>;

/// 成员列表游标，按成员关系创建时间和用户 ID 正序回传。
typedef GatheringMemberCursor = ({String createdAt, String userId});

/// listKey 仅用于列表去重，公开资料缺失的关系仍保留展示位置。
typedef GatheringMember = ({String listKey, GatheringPerson? user});
typedef GatheringMemberPageData =
    BrowsePage<GatheringMember, GatheringMemberCursor>;

/// 加入前展示目标信息，成员标记决定显示加入或直接进入。
class GatheringJoinPreview {
  const GatheringJoinPreview({
    required this.id,
    required this.name,
    required this.description,
    required this.creator,
    required this.isMember,
  });

  final String id;
  final String name;
  final String? description;
  final GatheringPerson? creator;
  final bool isMember;
}
