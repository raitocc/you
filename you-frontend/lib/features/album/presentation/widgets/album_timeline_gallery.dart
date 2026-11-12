import 'package:flutter/material.dart';
import 'album_cover.dart';

class AlbumTimelineGallery extends StatefulWidget {
  const AlbumTimelineGallery({super.key, required this.images});
  final List<String> images;

  @override
  State<AlbumTimelineGallery> createState() => _AlbumTimelineGalleryState();
}

class _AlbumTimelineGalleryState extends State<AlbumTimelineGallery> {
  int index = 0;

  @override
  Widget build(BuildContext context) {
    if (widget.images.length == 1) {
      return AlbumCover(url: widget.images.single, radius: 16);
    }
    return Stack(
      alignment: Alignment.bottomRight,
      children: [
        SizedBox(
          height: 220,
          child: PageView.builder(
            itemCount: widget.images.length,
            onPageChanged: (value) => setState(() => index = value),
            itemBuilder: (context, page) =>
                AlbumCover(url: widget.images[page], radius: 16),
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(12),
          child: Chip(
            label: Text(
              '${index.clamp(0, widget.images.length - 1) + 1} / ${widget.images.length}',
            ),
          ),
        ),
      ],
    );
  }
}
