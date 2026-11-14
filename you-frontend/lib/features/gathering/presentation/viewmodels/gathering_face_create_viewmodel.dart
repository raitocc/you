import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../../core/auth/session.dart';
import '../../../../core/location/device_location_service.dart';
import '../../data/gathering_repository.dart';
import '../../models/gathering.dart';
import 'gathering_list_viewmodel.dart';

typedef FaceToFaceCreateState = ({
  Gathering? created,
  bool busy,
  Object? error,
});
final gatheringFaceCreateProvider =
    NotifierProvider.autoDispose<
      GatheringFaceCreateViewModel,
      FaceToFaceCreateState
    >(GatheringFaceCreateViewModel.new);

/// 信息先留在表单，确认短码后定位、创建并开启；开启失败时保留已创建的同行供重试。
class GatheringFaceCreateViewModel extends Notifier<FaceToFaceCreateState> {
  @override
  FaceToFaceCreateState build() {
    ref.watch(sessionProvider);
    return (created: null, busy: false, error: null);
  }

  Future<Gathering?> create(
    String name,
    String description,
    String code,
  ) async {
    if (state.busy) return null;
    final token = ref.read(sessionProvider).asData?.value;
    state = (created: state.created, busy: true, error: null);
    try {
      final location = await ref.read(deviceLocationProvider).current();
      if (!_isCurrentSession(token)) return null;
      final gathering = await _createIfNeeded(name, description);
      if (!_isCurrentSession(token)) return null;
      state = (created: gathering, busy: true, error: null);
      ref.invalidate(gatheringListProvider);
      await ref
          .read(gatheringRepositoryProvider)
          .openFaceToFace(gathering.id, code, location);
      if (!_isCurrentSession(token)) return null;
      state = (created: gathering, busy: false, error: null);
      return gathering;
    } catch (error) {
      if (_isCurrentSession(token)) {
        state = (created: state.created, busy: false, error: error);
      }
      return null;
    }
  }

  /// 已创建时重试只开启短码，沿用同一条同行记录。
  Future<Gathering> _createIfNeeded(String name, String description) async {
    final created = state.created;
    if (created != null) return created;
    return ref
        .read(gatheringRepositoryProvider)
        .create(
          name: name.trim(),
          description: description.trim().isEmpty ? null : description,
        );
  }

  bool _isCurrentSession(String? token) =>
      ref.mounted && ref.read(sessionProvider).asData?.value == token;
}
