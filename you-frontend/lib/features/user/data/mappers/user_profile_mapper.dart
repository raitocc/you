import '../../models/user_profile.dart';

// 负责业务逻辑层与数据层之间的数据转换，将后端返回的 JSON 数据映射为 UserProfile 实体对象。
UserProfile userProfileFromJson(Map<String, dynamic> json) => UserProfile(
  id: json['id'] as String,
  username: json['username'] as String,
  nickname: json['nickname'] as String,
  avatarFileId: json['avatarFileId'] as String?,
  avatarUrl: json['avatarUrl'] as String?,
);
