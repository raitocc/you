import '../../models/moment.dart';
import '../../models/moment_draft.dart';

Map<String, dynamic> _location(MomentLocation location) => {
  'name': location.name,
  'address': location.address,
  'latitude': location.latitude,
  'longitude': location.longitude,
};

Map<String, dynamic> draftPayload(MomentDraft draft) => {
  if (draft.version == null) 'id': draft.id,
  'title': draft.title.trim().isEmpty ? null : draft.title,
  'body': draft.body.trim().isEmpty ? null : draft.body,
  'tags': draft.tags,
  'mood': draft.mood,
  if (draft.time != null) 'memoryTime': draft.time!.toUtc().toIso8601String(),
  'location': draft.location == null ? null : _location(draft.location!),
  'journeyId': draft.journeyId,
  'circleIds': draft.circleIds,
  'imageFileIds': draft.images.map((image) => image.fileId!).toList(),
};

Map<String, dynamic> draftToJson(MomentDraft draft) => {
  'id': draft.id,
  'version': draft.version,
  'title': draft.title,
  'body': draft.body,
  'tags': draft.tags,
  'mood': draft.mood,
  'time': draft.time?.toIso8601String(),
  'location': draft.location == null ? null : _location(draft.location!),
  'journeyId': draft.journeyId,
  'circleIds': draft.circleIds,
  'images': [
    for (final image in draft.images)
      {'id': image.fileId, 'path': image.localPath},
  ],
  'pending': draft.pending,
};

MomentDraft draftFromJson(Map<String, dynamic> json, Moment? current) {
  final location = json['location'] as Map<String, dynamic>?;
  final urls = {
    for (final image in current?.images ?? <MomentImage>[]) image.id: image.url,
  };
  return MomentDraft(
    id: json['id'] as String,
    version: json['version'] as String?,
    title: json['title'] as String,
    body: json['body'] as String,
    tags: List.unmodifiable((json['tags'] as List).cast<String>()),
    mood: json['mood'] as String?,
    time: DateTime.tryParse(json['time'] as String? ?? ''),
    location: location == null
        ? null
        : (
            name: location['name'] as String?,
            address: location['address'] as String?,
            latitude: location['latitude'] as num?,
            longitude: location['longitude'] as num?,
          ),
    journeyId: json['journeyId'] as String?,
    circleIds: List.unmodifiable((json['circleIds'] as List).cast<String>()),
    images: List.unmodifiable(
      (json['images'] as List).cast<Map<String, dynamic>>().map(
        (image) => DraftImage(
          fileId: image['id'] as String?,
          localPath: image['path'] as String?,
          url: urls[image['id']],
        ),
      ),
    ),
    pending: json['pending'] as Map<String, dynamic>?,
  );
}

MomentDraft draftFromMoment(Moment moment) => MomentDraft(
  id: moment.id,
  version: moment.version,
  title: moment.title ?? '',
  body: moment.body ?? '',
  tags: moment.tags,
  mood: moment.mood,
  time: moment.memoryTime,
  location: moment.location,
  journeyId: moment.sources
      .where((source) => source.type == 'journey')
      .firstOrNull
      ?.id,
  circleIds: List.unmodifiable(
    moment.sources
        .where((source) => source.type == 'circle')
        .map((source) => source.id),
  ),
  images: List.unmodifiable(
    moment.images.map((image) => DraftImage(fileId: image.id, url: image.url)),
  ),
);
