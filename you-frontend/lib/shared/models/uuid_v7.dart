import 'dart:math';

String uuidV7() {
  final random = Random.secure();
  final bytes = List.generate(16, (_) => random.nextInt(256));
  final milliseconds = DateTime.now().millisecondsSinceEpoch;
  for (var index = 0; index < 6; index++) {
    bytes[index] = (milliseconds >> ((5 - index) * 8)) & 255;
  }
  bytes[6] = 0x70 | (bytes[6] & 0x0f);
  bytes[8] = 0x80 | (bytes[8] & 0x3f);
  final hex = bytes
      .map((byte) => byte.toRadixString(16).padLeft(2, '0'))
      .join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-'
      '${hex.substring(16, 20)}-${hex.substring(20)}';
}
