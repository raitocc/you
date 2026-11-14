import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// 面对面入口先选择发起或加入，两条流程各自填写所需信息。
class GatheringFacePage extends StatelessWidget {
  const GatheringFacePage({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('面对面')),
    body: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text('和身边的朋友约定一串四位数字，一起加入同行。'),
          const SizedBox(height: 24),
          FilledButton.icon(
            onPressed: () => context.push('/gatherings/face-to-face/create'),
            icon: const Icon(Icons.add_location_alt_outlined),
            label: const Text('发起同行'),
          ),
          const SizedBox(height: 16),
          OutlinedButton.icon(
            onPressed: () => context.push('/gatherings/face-to-face/join'),
            icon: const Icon(Icons.near_me_outlined),
            label: const Text('加入同行'),
          ),
        ],
      ),
    ),
  );
}
