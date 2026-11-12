import 'package:flutter/material.dart';
import '../../../../core/network/api_client.dart';
import '../../models/circle.dart';
import '../../../../shared/models/moment_mood.dart';
import '../../../../shared/ui/mood_badge.dart';

class CircleProfileAvatar extends StatelessWidget {
  const CircleProfileAvatar(this.url, {super.key, this.radius = 24});
  final String? url;
  final double radius;

  @override
  Widget build(BuildContext context) => ClipOval(
    child: SizedBox.square(
      dimension: radius * 2,
      child: url == null
          ? _fallback(context)
          : Image.network(
              url!,
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) => _fallback(context),
            ),
    ),
  );

  Widget _fallback(BuildContext context) => ColoredBox(
    color: Theme.of(context).colorScheme.surfaceContainerHighest,
    child: const Icon(Icons.people_outline),
  );
}

class CircleImage extends StatelessWidget {
  const CircleImage(this.url, {super.key});
  final String url;
  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(16),
    child: AspectRatio(
      aspectRatio: 4 / 3,
      child: Image.network(
        url,
        fit: BoxFit.cover,
        errorBuilder: (_, _, _) =>
            const Center(child: Icon(Icons.broken_image_outlined)),
      ),
    ),
  );
}

class CircleMomentBody extends StatelessWidget {
  const CircleMomentBody(this.moment, {super.key});
  final CircleMoment moment;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        [
          MaterialLocalizations.of(
            context,
          ).formatCompactDate(moment.date.toLocal()),
          ?moment.place,
        ].join(' · '),
        style: Theme.of(context).textTheme.labelMedium,
      ),
      if (moment.title case final title?)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Text(title, style: Theme.of(context).textTheme.titleLarge),
        ),
      if (momentMoodLabel(moment.mood) case final mood?)
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: MoodBadge(mood),
        ),
      for (final url in moment.images)
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: CircleImage(url),
        ),
      if (moment.body case final body?) Text(body),
      if (moment.tags.isNotEmpty)
        Wrap(
          spacing: 8,
          children: [for (final tag in moment.tags) Chip(label: Text(tag))],
        ),
    ],
  );
}

class CircleMore extends StatelessWidget {
  const CircleMore({
    super.key,
    required this.busy,
    required this.hasMore,
    required this.error,
    required this.load,
  });
  final bool busy;
  final bool hasMore;
  final Object? error;
  final VoidCallback load;
  @override
  Widget build(BuildContext context) => Column(
    children: [
      if (error != null) Text(errorMessage(error)),
      if (busy)
        const CircularProgressIndicator()
      else if (hasMore)
        TextButton(onPressed: load, child: Text(error == null ? '加载更多' : '重试')),
    ],
  );
}

Future<bool> confirmCircleAction(
  BuildContext context,
  String title,
  String message,
) async =>
    await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('确认'),
          ),
        ],
      ),
    ) ??
    false;
