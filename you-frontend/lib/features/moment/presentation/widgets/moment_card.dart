import 'package:flutter/material.dart';

import '../../../../app/theme/app_theme.dart';
import '../../models/moment.dart';
import '../../models/moment_mood.dart';

import '../../../../shared/ui/mood_badge.dart';

export '../../../../shared/ui/mood_badge.dart';

String momentDate(DateTime time) {
  final local = time.toLocal();
  return '${local.year}.${local.month.toString().padLeft(2, '0')}.${local.day.toString().padLeft(2, '0')}';
}

final momentMoods = momentMoodCodes.keys;

class MomentNetworkImage extends StatelessWidget {
  const MomentNetworkImage(
    this.url, {
    super.key,
    this.fit = BoxFit.cover,
    this.onRetry,
    this.label = '瞬间照片',
  });
  final String url;
  final BoxFit fit;
  final VoidCallback? onRetry;
  final String label;

  @override
  Widget build(BuildContext context) => Image.network(
    url,
    width: double.infinity,
    height: double.infinity,
    fit: fit,
    semanticLabel: label,
    frameBuilder: (context, child, frame, synchronous) =>
        synchronous || frame != null
        ? child
        : const ColoredBox(
            color: Tokens.line,
            child: Center(
              child: Icon(Icons.image_outlined, color: Tokens.muted),
            ),
          ),
    errorBuilder: (context, error, stack) => ColoredBox(
      color: Tokens.line,
      child: Center(
        child: onRetry == null
            ? const Icon(Icons.broken_image_outlined, color: Tokens.muted)
            : IconButton(
                tooltip: '重新获取照片',
                onPressed: onRetry,
                icon: const Icon(Icons.refresh),
              ),
      ),
    ),
  );
}

class MomentCard extends StatelessWidget {
  const MomentCard({
    super.key,
    required this.moment,
    required this.onTap,
    this.footer,
  });
  final MomentFeedItem moment;
  final String? footer;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final coverUrl = moment.coverUrl;
    final hasImage = coverUrl != null;
    final title = moment.title?.trim() ?? '';
    final mood = moment.mood;
    return Semantics(
      button: true,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(15),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (hasImage)
              ClipRRect(
                borderRadius: BorderRadius.circular(15),
                child: AspectRatio(
                  aspectRatio: [0.82, 1.12, 0.96][moment.id.codeUnits.last % 3],
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      MomentNetworkImage(
                        coverUrl,
                        label: title.isEmpty ? '瞬间封面' : title,
                      ),
                      if (mood != null)
                        Positioned(left: 8, bottom: 8, child: MoodBadge(mood)),
                    ],
                  ),
                ),
              ),
            Container(
              margin: EdgeInsets.only(top: hasImage ? 7 : 0),
              padding: hasImage
                  ? const EdgeInsets.symmetric(horizontal: 4)
                  : const EdgeInsets.all(14),
              constraints: BoxConstraints(minHeight: hasImage ? 0 : 152),
              decoration: hasImage
                  ? null
                  : BoxDecoration(
                      color: const Color(0xFFF1F2E9),
                      borderRadius: BorderRadius.circular(15),
                    ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (!hasImage)
                    const Icon(
                      Icons.format_quote,
                      color: Tokens.accent,
                      size: 23,
                    ),
                  if (title.isNotEmpty)
                    Text(
                      title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: hasImage ? 14 : 17,
                      ),
                    ),
                  if (moment.bodyPreview?.trim().isNotEmpty ?? false)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(
                        moment.bodyPreview!,
                        maxLines: hasImage ? 2 : 5,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Tokens.muted,
                          fontSize: 13,
                          height: 1.55,
                        ),
                      ),
                    ),
                  if (!hasImage && mood != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 9),
                      child: MoodBadge(mood),
                    ),
                  Padding(
                    padding: const EdgeInsets.only(top: 7),
                    child: Text(
                      momentDate(moment.memoryTime),
                      style: const TextStyle(fontSize: 11, color: Tokens.muted),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(4, 6, 4, 0),
              child: Text(
                footer ??
                    (moment.imageCount > 1
                        ? '${moment.imageCount} 张照片'
                        : '查看瞬间'),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 11, color: Tokens.muted),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
