import '../../models/album.dart';

Album albumFromJson(Map<String, dynamic> json) => Album(
  id: json['id'] as String,
  name: json['name'] as String,
  description: json['description'] as String?,
  coverFileId: json['coverFileId'] as String?,
  coverUrl: json['coverUrl'] as String?,
  organizeMode: json['organizeMode'] as String,
  momentCount: json['momentCount'] as int,
  createdAt: DateTime.parse(json['createdAt'] as String),
  updatedAt: DateTime.parse(json['updatedAt'] as String),
);

AlbumTimelineItem albumTimelineItemFromJson(Map<String, dynamic> json) => (
  id: json['id'] as String,
  title: json['title'] as String?,
  body: json['body'] as String?,
  images: List<String>.unmodifiable(
    (json['images'] as List).map(
      (image) => (image as Map<String, dynamic>)['url'] as String,
    ),
  ),
  memoryTime: DateTime.parse(json['memoryTime'] as String),
  place: (json['location'] as Map<String, dynamic>?)?['name'] as String?,
  mood: json['mood'] as String?,
  tags: List<String>.unmodifiable((json['tags'] as List).cast<String>()),
);
