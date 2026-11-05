import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:you_app/core/storage/app_database.dart';

void main() {
  test('draft upsert preserves other accounts', () async {
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    addTearDown(db.close);
    await db.saveDraft('alice', 'draft-1', 'first');
    await db.saveDraft('bob', 'draft-1', 'private');
    await db.saveDraft('alice', 'draft-1', 'updated');
    expect((await db.draftsFor('alice')).single.payload, 'updated');
    expect((await db.draftsFor('bob')).single.payload, 'private');
  });
}
