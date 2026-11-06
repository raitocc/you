class UserProfile {
  const UserProfile({
    required this.id,
    required this.username,
    required this.nickname,
    this.avatarFileId,
    this.avatarUrl,
  });
  final String id, username, nickname;
  final String? avatarFileId;
  final String? avatarUrl;
}
