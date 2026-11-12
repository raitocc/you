import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../features/circle/data/circle_repository.dart';
import '../features/media/data/media_repository.dart';

CircleAvatarUpload assembleCircleAvatarUpload(Ref ref) =>
    (bytes, mime) =>
        ref.read(mediaRepositoryProvider).uploadReadyImage(bytes, mime);
