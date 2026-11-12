import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'app.dart';
import '../features/album/data/album_inputs.dart';
import 'album_composition.dart';
import 'circle_composition.dart';
import '../features/circle/data/circle_repository.dart';

Future<void> bootstrap() async {
  runApp(
    ProviderScope(
      overrides: [
        albumInputsProvider.overrideWith(assembleAlbumInputs),
        circleAvatarUploadProvider.overrideWith(assembleCircleAvatarUpload),
      ],
      child: const YouApp(),
    ),
  );
}
