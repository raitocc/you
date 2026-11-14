import 'package:flutter/material.dart';
import '../../models/face_to_face.dart';
import 'gathering_card.dart';
import 'face_to_face_countdown.dart';

/// 同码可匹配多个目标，每条明确展示创建者、距离和独立的加入入口。
class FaceToFaceCandidateCard extends StatelessWidget {
  const FaceToFaceCandidateCard({
    super.key,
    required this.candidate,
    required this.busy,
    required this.onConfirm,
  });
  final FaceToFaceCandidate candidate;
  final bool busy;
  final VoidCallback onConfirm;
  @override
  Widget build(BuildContext context) => FaceToFaceCountdown(
    expiresAt: candidate.expiresAt,
    builder: (context, remaining) => Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              candidate.gathering.name,
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 12),
            GatheringCreator(creator: candidate.gathering.creator),
            if (candidate.gathering.description?.isNotEmpty == true) ...[
              const SizedBox(height: 12),
              Text(candidate.gathering.description!),
            ],
            const SizedBox(height: 12),
            Text(
              '距发起位置约 ${candidate.distanceMeters.round()} 米 · ${FaceToFaceCountdown.label(remaining)}',
            ),
            const SizedBox(height: 12),
            FilledButton(
              onPressed:
                  busy || (!candidate.isMember && remaining <= Duration.zero)
                  ? null
                  : onConfirm,
              child: Text(
                candidate.isMember
                    ? '进入同行'
                    : remaining <= Duration.zero
                    ? '短码已过期'
                    : '确认加入',
              ),
            ),
          ],
        ),
      ),
    ),
  );
}
