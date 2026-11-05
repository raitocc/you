import 'package:drift/drift.dart';
import 'package:drift_flutter/drift_flutter.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../logging/app_logger.dart';

part 'app_database.g.dart';

class Drafts extends Table {
  TextColumn get ownerId => text()();
  TextColumn get draftId => text()();
  TextColumn get payload => text()();
  DateTimeColumn get updatedAt => dateTime()();
  @override
  Set<Column> get primaryKey => {ownerId, draftId};
}

@DriftDatabase(tables: [Drafts])
class AppDatabase extends _$AppDatabase {
  AppDatabase() : super(driftDatabase(name: 'you_private'));
  AppDatabase.forTesting(super.executor);
  @override
  int get schemaVersion => 1;
  Future<void> saveDraft(String ownerId, String draftId, String payload) =>
      AppLogger.track<void>(
        () => into(drafts).insertOnConflictUpdate(
          DraftsCompanion.insert(
            ownerId: ownerId,
            draftId: draftId,
            payload: payload,
            updatedAt: DateTime.now().toUtc(),
          ),
        ),
        started: AppLogEvent.storageDraftSaveStarted,
        succeeded: AppLogEvent.storageDraftSaveSucceeded,
        failed: AppLogEvent.storageDraftSaveFailed,
      );
  Future<List<Draft>> draftsFor(String ownerId) => AppLogger.track<List<Draft>>(
    () => (select(drafts)..where((t) => t.ownerId.equals(ownerId))).get(),
    started: AppLogEvent.storageDraftReadStarted,
    succeeded: AppLogEvent.storageDraftReadSucceeded,
    failed: AppLogEvent.storageDraftReadFailed,
  );
}

final databaseProvider = Provider((ref) {
  final database = AppDatabase();
  ref.onDispose(database.close);
  return database;
});
