import '../../models/gathering.dart';

/// 转换列表摘要，与详情的完整介绍分别建模。
GatheringSummary gatheringSummaryFromJson(Map<String, dynamic> json) =>
    GatheringSummary(
      status: GatheringStatus.fromJson(json['status'] as String),
      id: json['id'] as String,
      name: json['name'] as String,
      descriptionPreview: json['descriptionPreview'] as String?,
      creator: _personFromJson(json['creator'] as Map<String, dynamic>?),
      memberCount: json['memberCount'] as int,
      momentCount: json['momentCount'] as int,
      createdAt: DateTime.parse(json['createdAt'] as String),
    );

/// 转换基本信息，保持可空介绍和创建者的语义。
Gathering gatheringFromJson(Map<String, dynamic> json) => Gathering(
  status: GatheringStatus.fromJson(json['status'] as String),
  id: json['id'] as String,
  name: json['name'] as String,
  description: json['description'] as String?,
  creator: _personFromJson(json['creator'] as Map<String, dynamic>?),
  memberCount: json['memberCount'] as int,
  momentCount: json['momentCount'] as int,
  inviteCode: json['inviteCode'] as String,
  createdAt: DateTime.parse(json['createdAt'] as String),
  updatedAt: DateTime.parse(json['updatedAt'] as String),
);

/// 转换分页数据，保留游标时间原文。
GatheringPageData gatheringPageFromJson(Map<String, dynamic> json) {
  final cursor = json['nextCursor'] as Map<String, dynamic>?;
  return (
    items: List<GatheringSummary>.unmodifiable(
      (json['items'] as List).cast<Map<String, dynamic>>().map(
        gatheringSummaryFromJson,
      ),
    ),
    nextCursor: cursor == null
        ? null
        : (
            createdAt: cursor['createdAt'] as String,
            id: cursor['id'] as String,
          ),
    hasMore: json['hasMore'] as bool,
  );
}

/// 创建者资料缺失时保持 null，由界面明确展示。
GatheringPerson? _personFromJson(Map<String, dynamic>? json) {
  if (json == null) return null;
  return (
    id: json['id'] as String,
    nickname: json['nickname'] as String,
    avatarUrl: json['avatarUrl'] as String?,
  );
}

/// 保留空资料项；它们缺少用户 ID，使用请求游标和页内位置标记展示行。
GatheringMemberPageData gatheringMemberPageFromJson(
  Map<String, dynamic> json,
  GatheringMemberCursor? requestedCursor,
) {
  final items = json['items'] as List;
  final cursor = json['nextCursor'] as Map<String, dynamic>?;
  final members = <GatheringMember>[];
  for (var index = 0; index < items.length; index++) {
    final user = _personFromJson(items[index] as Map<String, dynamic>?);
    members.add((
      listKey:
          user?.id ?? 'missing:${requestedCursor?.userId ?? "first"}:$index',
      user: user,
    ));
  }
  return (
    items: List<GatheringMember>.unmodifiable(members),
    nextCursor: cursor == null
        ? null
        : (
            createdAt: cursor['createdAt'] as String,
            userId: cursor['userId'] as String,
          ),
    hasMore: json['hasMore'] as bool,
  );
}

/// 加入预览使用独立模型，保持与正式成员详情的字段区别。
GatheringJoinPreview gatheringJoinPreviewFromJson(Map<String, dynamic> json) =>
    GatheringJoinPreview(
      id: json['id'] as String,
      name: json['name'] as String,
      description: json['description'] as String?,
      creator: _personFromJson(json['creator'] as Map<String, dynamic>?),
      isMember: json['isMember'] as bool,
    );
