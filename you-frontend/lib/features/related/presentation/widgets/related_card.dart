import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../../app/theme/app_theme.dart';
import '../../../moment/models/moment.dart';
import '../../../moment/presentation/widgets/moment_card.dart';
import '../../models/related_entry.dart';

/// 单条分享动态：头像、分享者与来源、内容摘要、分享时间。
class RelatedCard extends StatelessWidget {
  const RelatedCard({super.key, required this.entry});
  final RelatedEntry entry;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.fromLTRB(18, 18, 18, 16),
    decoration: const BoxDecoration(
      border: Border(bottom: BorderSide(color: Tokens.line)),
    ),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _ActorAvatar(url: entry.actor?.avatarUrl),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                entry.actor?.nickname ?? '用户',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 15,
                  color: Tokens.accent,
                  fontWeight: FontWeight.w600,
                ),
              ),
              _ShareSource(source: entry.source),
              const SizedBox(height: 6),
              InkWell(
                onTap: () => _openContent(context),
                borderRadius: BorderRadius.circular(10),
                child: switch (entry) {
                  RelatedMomentEntry(:final moment) => _MomentContent(
                    moment: moment,
                  ),
                  RelatedAlbumEntry(:final album) => _AlbumContent(
                    album: album,
                  ),
                },
              ),
              const SizedBox(height: 12),
              Text(
                '分享于 ${_sharedTime(entry.sharedAt)}',
                style: const TextStyle(fontSize: 11, color: Tokens.muted),
              ),
            ],
          ),
        ),
      ],
    ),
  );

  /// 沿动态来源打开共享详情，服务端重新核验当前查看权限。
  void _openContent(BuildContext context) {
    final source = entry.source;
    final path = switch (entry) {
      RelatedMomentEntry(:final moment)
          when source.type == RelatedSourceType.gathering =>
        '/gatherings/${source.id}/moments/${moment.id}',
      RelatedMomentEntry() =>
        '/circles/${source.id}/shares/${entry.shareId!}/moment',
      RelatedAlbumEntry() => '/circles/${source.id}/shares/${entry.shareId!}',
    };
    context.push(path);
  }

  String _sharedTime(DateTime time) {
    final local = time.toLocal();
    final hour = local.hour.toString().padLeft(2, '0');
    final minute = local.minute.toString().padLeft(2, '0');
    return '${momentDate(local)} $hour:$minute';
  }
}

class _ActorAvatar extends StatelessWidget {
  const _ActorAvatar({required this.url});
  final String? url;

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(10),
    child: SizedBox.square(
      dimension: 40,
      child: url == null
          ? const ColoredBox(
              color: Tokens.green,
              child: Icon(Icons.person_outline, color: Tokens.accent, size: 24),
            )
          : MomentNetworkImage(url!, label: '分享者头像'),
    ),
  );
}

class _MomentContent extends StatelessWidget {
  const _MomentContent({required this.moment});
  final MomentFeedItem moment;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      if (moment.title case final title? when title.trim().isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(bottom: 6),
          child: Text(
            title,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontSize: 16,
              height: 1.4,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      if (moment.bodyPreview case final body? when body.trim().isNotEmpty)
        Text(
          body,
          maxLines: 5,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 14, height: 1.6, color: Tokens.ink),
        ),
      if (moment.coverUrl case final url?)
        Padding(
          padding: const EdgeInsets.only(top: 10),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 240),
            child: AspectRatio(
              aspectRatio: 1,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    MomentNetworkImage(url, label: '瞬间封面'),
                    if (moment.imageCount > 1)
                      Positioned(
                        bottom: 8,
                        right: 8,
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 8,
                            vertical: 4,
                          ),
                          decoration: BoxDecoration(
                            color: Colors.black54,
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            '${moment.imageCount} 张照片',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 11,
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
      if (moment.mood case final mood?)
        Padding(
          padding: const EdgeInsets.only(top: 10),
          child: MoodBadge(mood),
        ),
    ],
  );
}

/// 时光以紧凑的相册链接样式展示，和瞬间正文形成清楚区分。
class _AlbumContent extends StatelessWidget {
  const _AlbumContent({required this.album});
  final RelatedAlbumSummary album;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: Tokens.green,
      borderRadius: BorderRadius.circular(10),
    ),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: SizedBox.square(
            dimension: 64,
            child: album.coverUrl == null
                ? const Icon(
                    Icons.collections_bookmark_outlined,
                    size: 28,
                    color: Tokens.accent,
                  )
                : MomentNetworkImage(album.coverUrl!, label: album.name),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                album.name,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontWeight: FontWeight.w600,
                  fontSize: 15,
                ),
              ),
              if (album.descriptionPreview case final description?
                  when description.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(
                    description,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 12,
                      color: Tokens.muted,
                      height: 1.5,
                    ),
                  ),
                ),
              const SizedBox(height: 6),
              Text(
                '时光 · ${album.momentCount} 个瞬间',
                style: const TextStyle(fontSize: 11, color: Tokens.muted),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

class _ShareSource extends StatelessWidget {
  const _ShareSource({required this.source});
  final RelatedSource source;

  void _openSource(BuildContext context) {
    final path = switch (source.type) {
      RelatedSourceType.gathering => '/gatherings/${source.id}',
      RelatedSourceType.circle => Uri(
        path: '/circles',
        queryParameters: {'selected': source.id},
      ).toString(),
    };
    context.push(path);
  }

  @override
  Widget build(BuildContext context) {
    final label = source.type == RelatedSourceType.gathering ? '同行' : '圈子';
    return TextButton(
      onPressed: () => _openSource(context),
      style: TextButton.styleFrom(
        padding: EdgeInsets.zero,
        minimumSize: const Size(0, 40),
        alignment: Alignment.centerLeft,
        foregroundColor: Tokens.muted,
        textStyle: const TextStyle(fontSize: 12),
      ),
      child: Text(
        '$label · ${source.name}',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
    );
  }
}
