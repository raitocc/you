import 'moment.dart';

class DraftImage {
  const DraftImage({this.fileId, this.localPath, this.url});

  final String? fileId;
  final String? localPath;
  final String? url;
}

class MomentDraft {
  const MomentDraft({
    required this.id,
    this.version,
    this.title = '',
    this.body = '',
    this.tags = const [],
    this.mood,
    this.time,
    this.location,
    this.gatheringId,
    this.circleIds = const [],
    this.images = const [],
    this.pending,
  });

  final String id;
  final String? version;
  final String title;
  final String body;
  final List<String> tags;
  final String? mood;
  final DateTime? time;
  final MomentLocation? location;
  final String? gatheringId;
  final List<String> circleIds;
  final List<DraftImage> images;
  final Map<String, dynamic>? pending;

  MomentDraft withImages(List<DraftImage> images) => copyWith(images: images);

  MomentDraft withPending(Map<String, dynamic>? pending) =>
      copyWith(pending: () => pending);

  MomentDraft copyWith({
    String? version,
    String? title,
    String? body,
    List<String>? tags,
    String? Function()? mood,
    DateTime? time,
    MomentLocation? Function()? location,
    String? Function()? gatheringId,
    List<String>? circleIds,
    List<DraftImage>? images,
    Map<String, dynamic>? Function()? pending,
  }) => MomentDraft(
    id: id,
    version: version ?? this.version,
    title: title ?? this.title,
    body: body ?? this.body,
    tags: tags ?? this.tags,
    mood: mood == null ? this.mood : mood(),
    time: time ?? this.time,
    location: location == null ? this.location : location(),
    gatheringId: gatheringId == null ? this.gatheringId : gatheringId(),
    circleIds: circleIds ?? this.circleIds,
    images: images ?? this.images,
    pending: pending == null ? this.pending : pending(),
  );
}
