import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';

import '../../models/profile_edits.dart';

/// Temporary on-device storage for profile fields awaiting server support.
class ProfileLocalDataSource {
  Future<File> _file(String userId) async {
    final root = await getApplicationSupportDirectory();
    return File('${root.path}/profiles/${Uri.encodeComponent(userId)}.json');
  }

  /// 按账号读取保存在设备上的资料编辑。
  Future<ProfileEdits> read(String userId) async {
    final file = await _file(userId);
    final json = await file.exists()
        ? jsonDecode(await file.readAsString()) as Map<String, dynamic>
        : <String, dynamic>{};
    final avatar = json['avatar'] as String?;
    final background = json['background'] as String?;
    return (
      nickname: json['nickname'] as String?,
      signature: json['signature'] as String? ?? '',
      avatar: avatar == null ? null : base64Decode(avatar),
      background: background == null ? null : base64Decode(background),
    );
  }

  /// 将账号的本地资料编辑写入文件。
  Future<void> write(String userId, ProfileEdits edits) async {
    final file = await _file(userId);
    await file.parent.create(recursive: true);
    final pending = File('${file.path}.tmp');
    await pending.writeAsString(
      jsonEncode({
        'nickname': edits.nickname,
        'signature': edits.signature,
        'avatar': edits.avatar == null ? null : base64Encode(edits.avatar!),
        'background': edits.background == null
            ? null
            : base64Encode(edits.background!),
      }),
      flush: true,
    );
    await pending.rename(file.path);
  }
}
