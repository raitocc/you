import '../../models/face_to_face.dart';
import '../../models/gathering.dart';

FaceToFaceOpen faceToFaceOpenFromJson(Map<String, dynamic> json) => (
  gatheringId: json['gatheringId'] as String,
  faceCode: json['faceCode'] as String,
  expiresAt: DateTime.parse(json['expiresAt'] as String),
);

/// 保留全部候选及缺失的创建者资料，顺序沿用服务端的距离排序。
FaceToFaceCandidate faceToFaceCandidateFromJson(Map<String, dynamic> json) {
  final summary = json['gathering'] as Map<String, dynamic>;
  final person = summary['creator'] as Map<String, dynamic>?;
  final GatheringPerson? creator = person == null
      ? null
      : (
          id: person['id'] as String,
          nickname: person['nickname'] as String,
          avatarUrl: person['avatarUrl'] as String?,
        );
  return (
    gathering: (
      id: summary['id'] as String,
      name: summary['name'] as String,
      description: summary['description'] as String?,
      creator: creator,
    ),
    distanceMeters: (json['distanceMeters'] as num).toDouble(),
    expiresAt: DateTime.parse(json['expiresAt'] as String),
    isMember: json['isMember'] as bool,
  );
}
