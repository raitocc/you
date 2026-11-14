import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/logging/app_logger.dart';
import '../models/gathering.dart';
import '../models/face_to_face.dart';
import '../../../core/location/device_location_service.dart';
import 'mappers/face_to_face_mapper.dart';
import '../models/gathering_moment.dart';
import '../../moment/models/moment.dart';
import '../../moment/data/mappers/moment_mapper.dart';
import 'mappers/gathering_moment_mapper.dart';
import 'mappers/gathering_mapper.dart';
import 'remote/gathering_remote_data_source.dart';

final gatheringRepositoryProvider = Provider(
  (ref) => GatheringRepository(ref.watch(gatheringRemoteProvider)),
);

/// 对外提供类型明确的同行数据，模型转换集中在 mapper 中。
class GatheringRepository {
  GatheringRepository(this._remote);

  final GatheringRemoteDataSource _remote;

  /// 开启、查询和加入分别记录固定事件，日志保持坐标和短码私密。
  Future<FaceToFaceOpen> openFaceToFace(
    String id,
    String code,
    DeviceCoordinates location,
  ) => AppLogger.track(
    () async => faceToFaceOpenFromJson(
      await _remote.openFaceToFace(id, code, location),
    ),
    started: AppLogEvent.gatheringFaceOpenStarted,
    succeeded: AppLogEvent.gatheringFaceOpenSucceeded,
    failed: AppLogEvent.gatheringFaceOpenFailed,
  );

  Future<List<FaceToFaceCandidate>> previewFaceToFace(
    String code,
    DeviceCoordinates location,
  ) => AppLogger.track(
    () async {
      final result = await _remote.previewFaceToFace(code, location);
      return List<FaceToFaceCandidate>.unmodifiable(
        result.cast<Map<String, dynamic>>().map(faceToFaceCandidateFromJson),
      );
    },
    started: AppLogEvent.gatheringFacePreviewStarted,
    succeeded: AppLogEvent.gatheringFacePreviewSucceeded,
    failed: AppLogEvent.gatheringFacePreviewFailed,
  );

  Future<Gathering> joinFaceToFace(String id, String code) => AppLogger.track(
    () async => gatheringFromJson(await _remote.joinFaceToFace(id, code)),
    started: AppLogEvent.gatheringFaceJoinStarted,
    succeeded: AppLogEvent.gatheringFaceJoinSucceeded,
    failed: AppLogEvent.gatheringFaceJoinFailed,
  );

  /// 退出同行，记录操作结果；关联与成员关系由后端事务处理。
  Future<void> leave(String id) => AppLogger.track<void>(
    () => _remote.leave(id),
    started: AppLogEvent.gatheringLeaveStarted,
    succeeded: AppLogEvent.gatheringLeaveSucceeded,
    failed: AppLogEvent.gatheringLeaveFailed,
  );

  /// 结束后继续保留共同经历，日志与其他写操作保持一致。
  Future<void> end(String id) => AppLogger.track<void>(
    () => _remote.end(id),
    started: AppLogEvent.gatheringEndStarted,
    succeeded: AppLogEvent.gatheringEndSucceeded,
    failed: AppLogEvent.gatheringEndFailed,
  );

  /// 解散仅移除同行关系，原始内容仍由各作者管理。
  Future<void> dissolve(String id) => AppLogger.track<void>(
    () => _remote.dissolve(id),
    started: AppLogEvent.gatheringDissolveStarted,
    succeeded: AppLogEvent.gatheringDissolveSucceeded,
    failed: AppLogEvent.gatheringDissolveFailed,
  );

  /// 加载同行共享摘要，保持作者资料和游标时间精度。
  Future<GatheringMomentPageData> moments(
    String id, {
    MomentFeedCursor? cursor,
    int? limit,
  }) async => gatheringMomentPageFromJson(
    await _remote.moments(id, cursor: cursor, limit: limit),
  );

  /// 共享详情仅供阅读，编辑行为通过本人接口另行进入。
  Future<Moment> moment(String id, String momentId) async =>
      momentFromJson(await _remote.moment(id, momentId));

  /// 批量写操作记录固定日志事件，具体业务拒绝交给上层展示。
  Future<void> changeMoments(
    String id,
    List<String> momentIds, {
    required bool removing,
  }) => AppLogger.track<void>(
    () => _remote.changeMoments(id, momentIds, removing: removing),
    started: removing
        ? AppLogEvent.gatheringMomentsRemoveStarted
        : AppLogEvent.gatheringMomentsAddStarted,
    succeeded: removing
        ? AppLogEvent.gatheringMomentsRemoveSucceeded
        : AppLogEvent.gatheringMomentsAddSucceeded,
    failed: removing
        ? AppLogEvent.gatheringMomentsRemoveFailed
        : AppLogEvent.gatheringMomentsAddFailed,
  );

  /// 查询邀请码对应的加入预览。
  Future<GatheringJoinPreview> previewJoin(String inviteCode) async {
    final json = await _remote.previewJoin(inviteCode);
    return gatheringJoinPreviewFromJson(json);
  }

  /// 记录加入操作，成功返回完整同行信息。
  Future<Gathering> join(String inviteCode) => AppLogger.track<Gathering>(
    () async {
      final json = await _remote.join(inviteCode);
      return gatheringFromJson(json);
    },
    started: AppLogEvent.gatheringJoinStarted,
    succeeded: AppLogEvent.gatheringJoinSucceeded,
    failed: AppLogEvent.gatheringJoinFailed,
  );

  /// 浏览本人同行，返回可直接用于分页状态的数据。
  Future<GatheringPageData> browse({
    GatheringCursor? cursor,
    int? limit,
  }) async {
    final json = await _remote.browse(cursor: cursor, limit: limit);
    return gatheringPageFromJson(json);
  }

  /// 创建成功返回服务端信息；日志记录固定事件名。
  Future<Gathering> create({
    required String name,
    required String? description,
  }) => AppLogger.track<Gathering>(
    () async {
      final json = await _remote.create(name: name, description: description);
      return gatheringFromJson(json);
    },
    started: AppLogEvent.gatheringCreateStarted,
    succeeded: AppLogEvent.gatheringCreateSucceeded,
    failed: AppLogEvent.gatheringCreateFailed,
  );

  /// 获取详情；权限判断由服务端执行。
  Future<Gathering> detail(String id) async {
    final json = await _remote.detail(id);
    return gatheringFromJson(json);
  }

  /// 保存基本信息并记录关键写操作，返回最新同行信息。
  Future<Gathering> update(
    String id, {
    required String name,
    required String? description,
  }) => AppLogger.track<Gathering>(
    () async {
      final json = await _remote.update(
        id,
        name: name,
        description: description,
      );
      return gatheringFromJson(json);
    },
    started: AppLogEvent.gatheringUpdateStarted,
    succeeded: AppLogEvent.gatheringUpdateSucceeded,
    failed: AppLogEvent.gatheringUpdateFailed,
  );

  /// 成员按后端顺序展示，公开资料缺失的项保持原有位置。
  Future<GatheringMemberPageData> members(
    String id, {
    GatheringMemberCursor? cursor,
    int? limit,
  }) async {
    final json = await _remote.members(id, cursor: cursor, limit: limit);
    return gatheringMemberPageFromJson(json, cursor);
  }
}
