import 'package:flutter/material.dart';

import '../../../../app/theme/app_theme.dart';
import '../../models/moment.dart';
import 'moment_card.dart';

Future<DateTime?> showMomentCalendar(BuildContext context, DateTime initial) {
  var month = DateTime(initial.year, initial.month);
  var selected = DateUtils.dateOnly(initial);
  return _sheet(
    context,
    '记忆时间',
    (_) => [
      StatefulBuilder(
        builder: (context, setState) {
          final days = DateUtils.getDaysInMonth(month.year, month.month);
          final offset = month.weekday % 7;
          final rows = (offset + days + 6) ~/ 7;
          void move(int step) =>
              setState(() => month = DateTime(month.year, month.month + step));
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  IconButton(
                    tooltip: '上个月',
                    onPressed: () => move(-1),
                    icon: const Icon(Icons.chevron_left),
                  ),
                  Text(
                    '${month.year}年${month.month}月',
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                  IconButton(
                    tooltip: '下个月',
                    onPressed: () => move(1),
                    icon: const Icon(Icons.chevron_right),
                  ),
                ],
              ),
              Row(
                children: [
                  for (final day in ['日', '一', '二', '三', '四', '五', '六'])
                    Expanded(
                      child: Center(
                        child: Text(
                          day,
                          style: const TextStyle(color: Tokens.muted),
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 6),
              for (var row = 0; row < rows; row++)
                Row(
                  children: [
                    for (var column = 0; column < 7; column++)
                      Expanded(
                        child: Builder(
                          builder: (context) {
                            final day = row * 7 + column - offset + 1;
                            if (day < 1 || day > days) {
                              return const SizedBox(height: 44);
                            }
                            final date = DateTime(month.year, month.month, day);
                            final active = DateUtils.isSameDay(date, selected);
                            return Semantics(
                              selected: active,
                              label: '${month.month}月$day日',
                              child: TextButton(
                                onPressed: () =>
                                    setState(() => selected = date),
                                style: TextButton.styleFrom(
                                  backgroundColor: active ? Tokens.green : null,
                                  foregroundColor: Tokens.ink,
                                  minimumSize: const Size(0, 44),
                                  padding: EdgeInsets.zero,
                                ),
                                child: Text('$day'),
                              ),
                            );
                          },
                        ),
                      ),
                  ],
                ),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: () => Navigator.pop(context, selected),
                child: const Text('确认日期'),
              ),
            ],
          );
        },
      ),
    ],
  );
}

Future<String?> showMomentMood(BuildContext context, String? selected) =>
    _sheet(
      context,
      '此刻心情',
      (context) => [
        for (final mood in ['', ...momentMoods])
          ListTile(
            title: mood.isEmpty
                ? const Text('不设置心情')
                : Align(
                    alignment: Alignment.centerLeft,
                    child: MoodBadge(mood),
                  ),
            trailing: (selected ?? '') == mood
                ? const Icon(Icons.check, color: Tokens.accent)
                : null,
            onTap: () => Navigator.pop(context, mood),
          ),
      ],
    );

Future<List<String>?> showMomentTags(
  BuildContext context,
  List<String> selected,
) => _sheet(
  context,
  '编辑标签',
  (context) => [
    _SheetInput(
      initialText: selected.join('、'),
      builder: (context, controller) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: controller,
            autofocus: true,
            decoration: const InputDecoration(
              labelText: '标签',
              helperText: '用顿号或逗号分隔，最多 20 个标签',
            ),
          ),
          const SizedBox(height: 14),
          Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              for (final tag in ['日常碎片', '出走日记', '山野之间'])
                ActionChip(
                  label: Text('#$tag'),
                  onPressed: () {
                    final tags = _tags(controller.text);
                    if (!tags.contains(tag)) {
                      controller.text = [...tags, tag].join('、');
                    }
                  },
                ),
            ],
          ),
          const SizedBox(height: 18),
          FilledButton(
            onPressed: () => Navigator.pop(context, _tags(controller.text)),
            child: const Text('完成'),
          ),
        ],
      ),
    ),
  ],
);

List<String> _tags(String text) => text
    .split(RegExp(r'[,，、\n]'))
    .map((tag) => tag.trim())
    .where((tag) => tag.isNotEmpty)
    .toList();

Future<List<String>?> showMomentTargets(
  BuildContext context, {
  required bool journey,
  required List<String> selected,
  required List<MomentSource> known,
}) {
  final choices = {
    for (final source in known) source.id: source.name,
    for (final id in selected.where(
      (id) => !known.any((source) => source.id == id),
    ))
      id: id,
  };
  final picked = selected.toSet();
  String? error;
  final uuid = RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
    caseSensitive: false,
  );
  return _sheet(
    context,
    journey ? '关联同行' : '可见范围',
    (context) => [
      _SheetInput(
        builder: (context, controller) => StatefulBuilder(
          builder: (context, setState) {
            void toggle(String id, bool checked) => setState(() {
              if (journey) picked.clear();
              if (checked) {
                picked.add(id);
              } else {
                picked.remove(id);
              }
            });

            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (journey)
                  ListTile(
                    title: const Text('不关联同行'),
                    trailing: picked.isEmpty ? const Icon(Icons.check) : null,
                    onTap: () => setState(picked.clear),
                  ),
                for (final entry in choices.entries)
                  if (journey)
                    ListTile(
                      title: Text(entry.value),
                      trailing: picked.contains(entry.key)
                          ? const Icon(Icons.check)
                          : null,
                      onTap: () => toggle(entry.key, true),
                    )
                  else
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(entry.value),
                      value: picked.contains(entry.key),
                      onChanged: (value) => toggle(entry.key, value ?? false),
                    ),
                if (choices.isEmpty && !journey)
                  const Padding(
                    padding: EdgeInsets.only(bottom: 12),
                    child: Text(
                      '尚无已知圈子，未选择圈子时不会分享到圈子。',
                      style: TextStyle(color: Tokens.muted),
                    ),
                  ),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: TextField(
                        controller: controller,
                        decoration: InputDecoration(
                          labelText: journey ? '已有同行 ID' : '已有圈子 ID',
                          errorText: error,
                        ),
                        autocorrect: false,
                      ),
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      onPressed: () {
                        final id = controller.text.trim().toLowerCase();
                        if (!uuid.hasMatch(id)) {
                          setState(() => error = '请输入有效 ID');
                          return;
                        }
                        if (!journey &&
                            picked.length >= 100 &&
                            !picked.contains(id)) {
                          setState(() => error = '最多选择 100 个圈子');
                          return;
                        }
                        setState(() {
                          error = null;
                          choices.putIfAbsent(id, () => id);
                          controller.clear();
                        });
                        toggle(id, true);
                      },
                      child: const Text('添加'),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                const Text(
                  '提交时会由服务端检查成员资格。',
                  style: TextStyle(color: Tokens.muted),
                ),
                const SizedBox(height: 14),
                FilledButton(
                  onPressed: () => Navigator.pop(context, picked.toList()),
                  child: const Text('完成'),
                ),
              ],
            );
          },
        ),
      ),
    ],
  );
}

/// 控制器随输入面板卸载，退出动画期间仍可使用。
class _SheetInput extends StatefulWidget {
  const _SheetInput({this.initialText = '', required this.builder});

  final String initialText;
  final Widget Function(BuildContext, TextEditingController) builder;

  @override
  State<_SheetInput> createState() => _SheetInputState();
}

class _SheetInputState extends State<_SheetInput> {
  late final _controller = TextEditingController(text: widget.initialText);

  @override
  Widget build(BuildContext context) => widget.builder(context, _controller);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }
}

Future<T?> _sheet<T>(
  BuildContext context,
  String title,
  List<Widget> Function(BuildContext) children,
) => showModalBottomSheet<T>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  showDragHandle: true,
  backgroundColor: Colors.white,
  builder: (context) => Padding(
    padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
    child: SafeArea(
      top: false,
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Text(title, style: Theme.of(context).textTheme.titleLarge),
            ),
            ...children(context),
          ],
        ),
      ),
    ),
  ),
);
