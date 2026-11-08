import 'package:flutter/material.dart';

class AlbumCover extends StatelessWidget {
  const AlbumCover({super.key, this.url, this.height = 220, this.radius = 24});
  final String? url;
  final double height, radius;
  @override
  Widget build(BuildContext context) {
    final fallback = ColoredBox(
      color: Theme.of(context).colorScheme.secondaryContainer,
      child: const Center(child: Icon(Icons.auto_stories_outlined, size: 48)),
    );
    return SizedBox(
      height: height,
      width: double.infinity,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: url == null
            ? fallback
            : Image.network(
                url!,
                fit: BoxFit.cover,
                errorBuilder: (_, _, _) => fallback,
              ),
      ),
    );
  }
}
