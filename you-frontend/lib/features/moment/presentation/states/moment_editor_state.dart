import '../../models/moment.dart';
import '../../models/moment_draft.dart';

typedef MomentEditorState = ({
  MomentDraft draft,
  Moment? original,
  bool dirty,
  String? progress,
  Object? error,
});
