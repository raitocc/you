class UserProfile {
  const UserProfile({
    required this.id,
    required this.username,
    required this.nickname,
    this.avatarFileId,
  });
  final String id, username, nickname;
  final String? avatarFileId;
}
