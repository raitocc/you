typedef CircleSummary = ({String id, String name, String? avatarUrl});
typedef CirclePerson = ({String id, String name, String? avatarUrl});
typedef CircleMember = ({CirclePerson user, bool isCreator});
typedef CircleDetails = ({
  CircleSummary circle,
  String? description,
  String? avatarFileId,
  bool isCreator,
  List<CircleMember> members,
});
typedef CirclePreview = ({
  CircleSummary circle,
  String? description,
  CirclePerson creator,
  bool isMember,
});
typedef CircleInvitation = ({bool enabled, int? code});
typedef CircleCursor = ({String time, String id});
typedef CircleMoment = ({
  String id,
  String? title,
  String? body,
  DateTime date,
  String? place,
  List<String> images,
  List<String> tags,
  String? mood,
});
typedef SharedAlbum = ({
  String name,
  String? description,
  String? cover,
  int count,
});
typedef CircleShare = ({
  String id,
  CirclePerson sharer,
  DateTime date,
  bool canWithdraw,
  CircleMoment? moment,
  SharedAlbum? album,
});

enum CircleShareType { moment, album }
