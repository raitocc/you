import 'gathering.dart';

/// 一次开启的服务端回执，截止时间用于倒计时。
typedef FaceToFaceOpen = ({
  String gatheringId,
  String faceCode,
  DateTime expiresAt,
});

/// 附近预览的独立摘要，字段与正式成员详情分别建模。
typedef FaceToFaceSummary = ({
  String id,
  String name,
  String? description,
  GatheringPerson? creator,
});
typedef FaceToFaceCandidate = ({
  FaceToFaceSummary gathering,
  double distanceMeters,
  DateTime expiresAt,
  bool isMember,
});

abstract final class FaceToFaceRules {
  static String? validateCode(String? value) =>
      RegExp(r'^[0-9]{4}$').hasMatch(value ?? '') ? null : '请输入四位数字短码';
}
