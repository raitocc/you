import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/network/api_client.dart';
import '../../../shared/models/cursor_page.dart';
import '../models/related_entry.dart';
import 'mappers/related_mapper.dart';
import 'remote/related_remote_data_source.dart';

final relatedRepositoryProvider = Provider(
  (ref) => RelatedRepository(RelatedRemoteDataSource(ref.watch(dioProvider))),
);

/// 为动态列表提供明确类型，HTTP 与 JSON 转换分别交给对应组件。
class RelatedRepository {
  RelatedRepository(this._remote);
  final RelatedRemoteDataSource _remote;

  Future<BrowsePage<RelatedEntry, RelatedCursor>> browse({
    RelatedCursor? cursor,
    int? limit,
  }) async =>
      relatedPageFromJson(await _remote.browse(cursor: cursor, limit: limit));
}
