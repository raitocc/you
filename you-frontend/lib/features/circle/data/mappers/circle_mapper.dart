import '../../../../shared/models/cursor_page.dart';
import '../../models/circle.dart';

typedef Json = Map<String, dynamic>;

CircleSummary circleSummary(Json json) => (
  id: json['id'] as String,
  name: json['name'] as String,
  avatarUrl: json['avatarUrl'] as String?,
);

CirclePerson circlePerson(Json json) => (
  id: json['id'] as String,
  name: json['nickname'] as String,
  avatarUrl: json['avatarUrl'] as String?,
);

CircleDetails circleDetails(Json json) => (
  circle: circleSummary(json),
  description: json['description'] as String?,
  avatarFileId: json['avatarFileId'] as String?,
  isCreator: json['isCreator'] as bool,
  members: List.unmodifiable(
    (json['members'] as List).cast<Json>().map(
      (member) => (
        user: circlePerson(member['user'] as Json),
        isCreator: member['isCreator'] as bool,
      ),
    ),
  ),
);

CirclePreview circlePreview(Json json) {
  final circle = json['circle'] as Json;
  return (
    circle: circleSummary(circle),
    description: circle['description'] as String?,
    creator: circlePerson(circle['creator'] as Json),
    isMember: json['isMember'] as bool,
  );
}

CircleInvitation circleInvitation(Json json) =>
    (enabled: json['inviteEnabled'] as bool, code: json['inviteCode'] as int?);

CircleMoment circleMoment(Json json) => (
  id: json['id'] as String,
  title: json['title'] as String?,
  mood: json['mood'] as String?,
  body: json['body'] as String?,
  date: DateTime.parse(json['memoryTime'] as String),
  place: (json['location'] as Json?)?['name'] as String?,
  images: List.unmodifiable(
    (json['images'] as List).cast<Json>().map(
      (image) => image['url'] as String,
    ),
  ),
  tags: List.unmodifiable((json['tags'] as List).cast<String>()),
);

CircleShare circleShare(Json json) {
  final moment = json['moment'] as Json?;
  final album = json['album'] as Json?;
  return (
    id: json['id'] as String,
    sharer: circlePerson(json['sharer'] as Json),
    date: DateTime.parse(json['sharedAt'] as String),
    canWithdraw: json['canWithdraw'] as bool,
    moment: moment == null ? null : circleMoment(moment),
    album: album == null
        ? null
        : (
            name: album['name'] as String,
            description: album['description'] as String?,
            cover: album['coverUrl'] as String?,
            count: album['momentCount'] as int,
          ),
  );
}

BrowsePage<T, CircleCursor> circlePage<T>(
  Json json,
  String timeKey,
  T Function(Json) decode,
) {
  final next = json['nextCursor'] as Json?;
  return (
    items: List.unmodifiable((json['items'] as List).cast<Json>().map(decode)),
    nextCursor: next == null
        ? null
        : (time: next[timeKey] as String, id: next['id'] as String),
    hasMore: json['hasMore'] as bool,
  );
}
