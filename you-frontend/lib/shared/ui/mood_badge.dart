import 'package:flutter/material.dart';

class MoodBadge extends StatelessWidget {
  const MoodBadge(this.mood, {super.key});
  final String mood;

  @override
  Widget build(BuildContext context) {
    final (icon, color) = switch (mood) {
      '开心' => (Icons.sentiment_very_satisfied, const Color(0xFFF8EBCB)),
      '心动' => (Icons.favorite_border, const Color(0xFFF6E3E5)),
      '惊喜' => (Icons.auto_awesome_outlined, const Color(0xFFF4EACF)),
      '低落' => (Icons.sentiment_dissatisfied, const Color(0xFFE4E9F0)),
      _ => (Icons.sentiment_satisfied_alt, const Color(0xFFE8EDDF)),
    };
    return DecoratedBox(
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 14, color: Theme.of(context).colorScheme.primary),
            const SizedBox(width: 4),
            Text(mood, style: const TextStyle(fontSize: 12)),
          ],
        ),
      ),
    );
  }
}
