import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../models/face_to_face.dart';
import '../viewmodels/gathering_face_join_viewmodel.dart';
import '../widgets/face_code_field.dart';
import '../widgets/face_to_face_candidate_card.dart';
import '../widgets/face_to_face_error.dart';

/// 输入短码后定位，预览全部同码候选，再由用户确认目标。
class GatheringFaceJoinPage extends ConsumerStatefulWidget {
  const GatheringFaceJoinPage({super.key});
  @override
  ConsumerState<GatheringFaceJoinPage> createState() =>
      _GatheringFaceJoinPageState();
}

class _GatheringFaceJoinPageState extends ConsumerState<GatheringFaceJoinPage> {
  final _form = GlobalKey<FormState>();
  final _code = TextEditingController();
  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(gatheringFaceJoinProvider);
    return PopScope(
      canPop: !state.busy,
      child: Scaffold(
        appBar: AppBar(title: const Text('加入同行')),
        body: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            const Text('和身边的朋友约定同一串四位数字，查找发起位置 100 米内的同行。'),
            const SizedBox(height: 12),
            Form(
              key: _form,
              child: FaceCodeField(
                controller: _code,
                enabled: !state.busy,
                onChanged: ref
                    .read(gatheringFaceJoinProvider.notifier)
                    .changeCode,
              ),
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: state.busy ? null : _preview,
              child: Text(state.busy ? '处理中…' : '查找附近同行'),
            ),
            if (state.busy) const LinearProgressIndicator(),
            if (state.error != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: FaceToFaceError(error: state.error!),
              ),
            if (state.searched && state.candidates.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Text('附近暂无有效同行，请核对短码、位置和有效期后重试。'),
              ),
            for (final candidate in state.candidates)
              FaceToFaceCandidateCard(
                key: ValueKey(candidate.gathering.id),
                candidate: candidate,
                busy: state.busy,
                onConfirm: () => _confirm(candidate),
              ),
          ],
        ),
      ),
    );
  }

  void _preview() {
    if (!_form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    ref.read(gatheringFaceJoinProvider.notifier).preview(_code.text);
  }

  Future<void> _confirm(FaceToFaceCandidate target) async {
    if (target.isMember) {
      context.replace('/gatherings/${target.gathering.id}');
      return;
    }
    final result = await ref
        .read(gatheringFaceJoinProvider.notifier)
        .join(target);
    if (mounted && result != null) context.replace('/gatherings/${result.id}');
  }
}
