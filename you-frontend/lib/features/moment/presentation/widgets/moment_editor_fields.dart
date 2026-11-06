import 'dart:io';

import 'package:flutter/material.dart';

import '../../../../app/theme/app_theme.dart';
import '../../models/moment_draft.dart';
import 'moment_card.dart';

class MomentEditorField extends StatelessWidget {
  const MomentEditorField({
    super.key,
    required this.initial,
    required this.label,
    required this.hint,
    required this.enabled,
    required this.onChanged,
    this.style,
    this.maxLength,
    this.minLines = 1,
  });
  final String initial, label, hint;
  final bool enabled;
  final ValueChanged<String> onChanged;
  final TextStyle? style;
  final int? maxLength;
  final int minLines;

  @override
  Widget build(BuildContext context) => TextFormField(
    initialValue: initial,
    enabled: enabled,
    maxLength: maxLength,
    minLines: minLines,
    maxLines: minLines == 1 ? 1 : null,
    style: style,
    onChanged: onChanged,
    decoration: InputDecoration(
      labelText: label,
      hintText: hint,
      alignLabelWithHint: true,
      counterText: '',
      border: const UnderlineInputBorder(),
      enabledBorder: const UnderlineInputBorder(
        borderSide: BorderSide(color: Tokens.line),
      ),
      contentPadding: const EdgeInsets.symmetric(vertical: 14),
    ),
  );
}

class MomentEditorRow extends StatelessWidget {
  const MomentEditorRow({
    super.key,
    required this.title,
    required this.value,
    required this.icon,
    this.onTap,
  });
  final String title, value;
  final IconData icon;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      ListTile(
        contentPadding: EdgeInsets.zero,
        onTap: onTap,
        leading: Icon(icon, size: 20, color: Tokens.accent),
        title: Text(title, style: const TextStyle(fontSize: 14)),
        subtitle: Text(
          value,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(color: Tokens.muted),
        ),
        trailing: const Icon(Icons.chevron_right),
      ),
      const Divider(height: 1),
    ],
  );
}

class MomentImageStrip extends StatelessWidget {
  const MomentImageStrip({
    super.key,
    required this.images,
    this.onAdd,
    this.onRemove,
  });
  final List<DraftImage> images;
  final VoidCallback? onAdd;
  final ValueChanged<int>? onRemove;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 104,
    child: ListView.separated(
      scrollDirection: Axis.horizontal,
      itemCount: images.length + 1,
      separatorBuilder: (_, _) => const SizedBox(width: 8),
      itemBuilder: (context, index) {
        if (index == images.length) {
          return SizedBox(
            width: 94,
            child: Material(
              color: Tokens.green,
              borderRadius: BorderRadius.circular(12),
              child: InkWell(
                borderRadius: BorderRadius.circular(12),
                onTap: onAdd,
                child: const Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.add_photo_alternate_outlined),
                    SizedBox(height: 4),
                    Text('添加照片', style: TextStyle(fontSize: 12)),
                  ],
                ),
              ),
            ),
          );
        }
        final image = images[index];
        return SizedBox(
          width: 94,
          child: Stack(
            fit: StackFit.expand,
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: image.localPath != null
                    ? Image.file(
                        File(image.localPath!),
                        fit: BoxFit.cover,
                        errorBuilder: (_, _, _) =>
                            const Icon(Icons.broken_image_outlined),
                      )
                    : image.url != null
                    ? MomentNetworkImage(image.url!)
                    : const ColoredBox(
                        color: Tokens.line,
                        child: Icon(Icons.image_outlined),
                      ),
              ),
              if (onRemove != null)
                Positioned(
                  top: 0,
                  right: 0,
                  child: IconButton(
                    tooltip: '移除第 ${index + 1} 张照片',
                    onPressed: () => onRemove!(index),
                    icon: const Icon(
                      Icons.cancel,
                      color: Colors.white,
                      shadows: [Shadow(blurRadius: 4, color: Colors.black87)],
                    ),
                  ),
                ),
            ],
          ),
        );
      },
    ),
  );
}
