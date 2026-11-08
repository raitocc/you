import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../features/album/data/album_inputs.dart';
import '../features/media/data/media_repository.dart';
import '../features/moment/data/moment_repository.dart';

AlbumInputs assembleAlbumInputs(Ref ref) => AlbumInputs(
  moments: (cursor) async {
    final page = await ref
        .read(momentRepositoryProvider)
        .browseYours(cursor: cursor);
    return (
      items: page.items
          .map(
            (item) => (
              id: item.id,
              title: item.title,
              preview: item.bodyPreview,
              coverUrl: item.coverUrl,
            ),
          )
          .toList(),
      cursor: page.nextCursor,
      hasMore: page.hasMore,
    );
  },
  upload: (bytes, mime) =>
      ref.read(mediaRepositoryProvider).upload(bytes, mime),
  isReady: (id) => ref.read(mediaRepositoryProvider).isUploadReady(id),
);
