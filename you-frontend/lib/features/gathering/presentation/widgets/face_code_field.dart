import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../models/face_to_face.dart';

/// 短码始终使用字符串，数字键盘和过滤器保留 0005 这样的前导零。
class FaceCodeField extends StatelessWidget {
  const FaceCodeField({
    super.key,
    required this.controller,
    required this.enabled,
    this.onChanged,
  });
  final TextEditingController controller;
  final bool enabled;
  final ValueChanged<String>? onChanged;
  @override
  Widget build(BuildContext context) => TextFormField(
    controller: controller,
    enabled: enabled,
    onChanged: onChanged,
    keyboardType: TextInputType.number,
    inputFormatters: [
      FilteringTextInputFormatter.digitsOnly,
      LengthLimitingTextInputFormatter(4),
    ],
    decoration: const InputDecoration(labelText: '四位数字短码', hintText: '例如 0005'),
    validator: FaceToFaceRules.validateCode,
  );
}
