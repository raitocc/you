import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/auth/session.dart';
import '../../../../core/location/device_location_service.dart';
import '../../data/gathering_repository.dart';
import '../../models/face_to_face.dart';
import '../../models/gathering.dart';
import 'gathering_detail_viewmodel.dart';
import 'gathering_list_viewmodel.dart';
import 'gathering_members_viewmodel.dart';

typedef FaceToFaceJoinState = ({
  String code,
  List<FaceToFaceCandidate> candidates,
  bool busy,
  bool searched,
  Object? error,
});
final gatheringFaceJoinProvider =
    NotifierProvider.autoDispose<
      GatheringFaceJoinViewModel,
      FaceToFaceJoinState
    >(GatheringFaceJoinViewModel.new);

/// 查询用当前位置，确认用已查询的短码和选中的 ID；编辑短码清除旧候选。
class GatheringFaceJoinViewModel extends Notifier<FaceToFaceJoinState> {
  @override
  FaceToFaceJoinState build() {
    ref.watch(sessionProvider);
    return (
      code: '',
      candidates: const [],
      busy: false,
      searched: false,
      error: null,
    );
  }

  void changeCode(String code) {
    if (state.busy) return;
    state = (
      code: code,
      candidates: const [],
      busy: false,
      searched: false,
      error: null,
    );
  }

  Future<void> preview(String code) async {
    if (state.busy) return;
    final token = ref.read(sessionProvider).asData?.value;
    state = (
      code: code,
      candidates: const [],
      busy: true,
      searched: false,
      error: null,
    );
    try {
      final location = await ref.read(deviceLocationProvider).current();
      if (!_isCurrentSession(token)) return;
      final result = await ref
          .read(gatheringRepositoryProvider)
          .previewFaceToFace(code, location);
      if (_isCurrentSession(token)) {
        state = (
          code: code,
          candidates: result,
          busy: false,
          searched: true,
          error: null,
        );
      }
    } catch (error) {
      if (_isCurrentSession(token)) {
        state = (
          code: code,
          candidates: const [],
          busy: false,
          searched: false,
          error: error,
        );
      }
    }
  }

  Future<Gathering?> join(FaceToFaceCandidate target) async {
    if (state.busy ||
        target.isMember ||
        !target.expiresAt.isAfter(DateTime.now())) {
      return null;
    }
    final current = state;
    final token = ref.read(sessionProvider).asData?.value;
    state = (
      code: current.code,
      candidates: current.candidates,
      busy: true,
      searched: true,
      error: null,
    );
    try {
      final result = await ref
          .read(gatheringRepositoryProvider)
          .joinFaceToFace(target.gathering.id, current.code);
      if (!_isCurrentSession(token)) return null;
      ref.invalidate(gatheringListProvider);
      ref.invalidate(gatheringDetailProvider(result.id));
      ref.invalidate(gatheringMembersProvider(result.id));
      state = (
        code: current.code,
        candidates: current.candidates,
        busy: false,
        searched: true,
        error: null,
      );
      return result;
    } catch (error) {
      if (_isCurrentSession(token)) {
        state = (
          code: current.code,
          candidates: current.candidates,
          busy: false,
          searched: true,
          error: error,
        );
      }
      return null;
    }
  }

  bool _isCurrentSession(String? token) =>
      ref.mounted && ref.read(sessionProvider).asData?.value == token;
}
