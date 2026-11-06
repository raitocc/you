import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/auth/session.dart';
import '../../data/moment_repository.dart';
import '../../models/moment.dart';

final momentDetailProvider = AsyncNotifierProvider.autoDispose
    .family<MomentDetailViewModel, Moment, String>(MomentDetailViewModel.new);

class MomentDetailViewModel extends AsyncNotifier<Moment> {
  MomentDetailViewModel(this.id);

  final String id;

  @override
  Future<Moment> build() {
    ref.watch(sessionProvider);
    ref.watch(momentRevisionProvider);
    return ref.watch(momentRepositoryProvider).detail(id);
  }

  Future<void> delete(Moment moment) async {
    final token = ref.read(sessionProvider).asData?.value;
    await ref.read(momentRepositoryProvider).delete(id, moment.version);
    if (ref.mounted && token == ref.read(sessionProvider).asData?.value) {
      ref.read(momentRevisionProvider.notifier).changed();
    }
  }
}
