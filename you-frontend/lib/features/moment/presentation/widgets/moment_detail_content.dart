import 'dart:ui' show PointerDeviceKind;
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import '../../../../app/theme/app_theme.dart';
import '../../models/moment.dart';
import 'moment_card.dart';

/// 复用照片、正文和作者展示，导航与操作由本人或同行页面提供。
class MomentDetailContent extends StatelessWidget {
  const MomentDetailContent({
    super.key,
    required this.moment,
    required this.nav,
    required this.onRefresh,
    this.busy = false,
    this.visibilityLabel,
  });
  final Moment moment;
  final Widget nav;
  final VoidCallback onRefresh;
  final bool busy;
  final String? visibilityLabel;

  @override
  Widget build(BuildContext context) {
    final author = moment.author;
    return SafeArea(
      child: ListView(
        padding: EdgeInsets.zero,
        children: [
          if (moment.images.isNotEmpty)
            Stack(
              children: [
                _Gallery(
                  key: ValueKey(moment.id),
                  images: moment.images,
                  retry: () => onRefresh(),
                ),
                Positioned(
                  top: 6,
                  left: 10,
                  right: 10,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.89),
                      borderRadius: BorderRadius.circular(32),
                    ),
                    child: nav,
                  ),
                ),
              ],
            )
          else
            nav,
          if (busy) const LinearProgressIndicator(),
          Padding(
            padding: const EdgeInsets.fromLTRB(18, 14, 18, 10),
            child: Row(
              children: [
                ClipOval(
                  child: SizedBox(
                    width: 40,
                    height: 40,
                    child: author?.avatarUrl == null
                        ? const ColoredBox(
                            color: Tokens.green,
                            child: Icon(Icons.person_outline),
                          )
                        : MomentNetworkImage(author!.avatarUrl!, label: '作者头像'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        author == null
                            ? '用户资料暂无'
                            : author.nickname ?? (moment.isOwn ? '我' : '用户'),
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                      const Text(
                        '记录生活的瞬间',
                        style: TextStyle(color: Tokens.muted, fontSize: 12),
                      ),
                    ],
                  ),
                ),
                Chip(
                  label: Text(
                    visibilityLabel ??
                        (moment.sources.isEmpty ? '瞬间记录' : '已分享'),
                  ),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 30),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (moment.mood case final mood?) MoodBadge(mood),
                if (moment.title?.isNotEmpty ?? false)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(
                      moment.title!,
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                  ),
                if (moment.body?.isNotEmpty ?? false)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    child: SelectableText(
                      moment.body!,
                      style: const TextStyle(
                        fontSize: 16,
                        height: 1.8,
                        color: Tokens.ink,
                      ),
                    ),
                  ),
                if (moment.tags.isNotEmpty)
                  Wrap(
                    spacing: 10,
                    runSpacing: 6,
                    children: [
                      for (final tag in moment.tags)
                        Text(
                          '#$tag',
                          style: const TextStyle(color: Tokens.accent),
                        ),
                    ],
                  ),
                const SizedBox(height: 14),
                Text(
                  momentDate(moment.memoryTime),
                  style: const TextStyle(color: Tokens.muted),
                ),
                if (moment.location case final location?)
                  if (location.name != null || location.address != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(
                        [location.name, location.address].nonNulls.join(' · '),
                      ),
                    ),
                if (moment.sources.isNotEmpty || moment.collections.isNotEmpty)
                  const Divider(height: 32),
                for (final source in moment.sources)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: Icon(
                      source.type == 'journey'
                          ? Icons.groups_outlined
                          : Icons.group_work_outlined,
                    ),
                    title: Text(source.name),
                    subtitle: Text(source.type == 'journey' ? '来自同行' : '来自圈子'),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () => context.push(
                      source.type == 'journey'
                          ? '/gatherings/${source.id}'
                          : '/circles/${source.id}',
                    ),
                  ),
                for (final collection in moment.collections)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.photo_album_outlined),
                    title: Text(collection.name),
                    subtitle: const Text('收录于时光'),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () => context.push('/albums/${collection.id}'),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Gallery extends StatefulWidget {
  const _Gallery({super.key, required this.images, required this.retry});
  final List<MomentImage> images;
  final VoidCallback retry;

  @override
  State<_Gallery> createState() => _GalleryState();
}

class _GalleryState extends State<_Gallery> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    final count = widget.images.length;
    return SizedBox(
      height: (MediaQuery.sizeOf(context).width * 1.03).clamp(250.0, 480.0),
      child: Stack(
        fit: StackFit.expand,
        children: [
          const ColoredBox(color: Tokens.line),
          ScrollConfiguration(
            behavior: ScrollConfiguration.of(context).copyWith(
              dragDevices: {
                PointerDeviceKind.touch,
                PointerDeviceKind.mouse,
                PointerDeviceKind.trackpad,
                PointerDeviceKind.stylus,
              },
            ),
            child: PageView.builder(
              itemCount: count,
              onPageChanged: (index) => setState(() => _index = index),
              itemBuilder: (context, index) => MomentNetworkImage(
                widget.images[index].url,
                fit: BoxFit.contain,
                onRetry: widget.retry,
                label: '第 ${index + 1} 张照片，共 $count 张',
              ),
            ),
          ),
          if (count > 1)
            Positioned(
              bottom: 28,
              right: 14,
              child: Chip(
                label: Text('${(_index + 1).clamp(1, count)} / $count'),
              ),
            ),
          if (count > 1)
            Positioned(
              bottom: 12,
              left: 0,
              right: 0,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  for (var index = 0; index < count; index++)
                    Container(
                      margin: const EdgeInsets.symmetric(horizontal: 2),
                      width: 5,
                      height: 5,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: index == _index
                            ? Tokens.ink
                            : Tokens.muted.withValues(alpha: 0.4),
                      ),
                    ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}
