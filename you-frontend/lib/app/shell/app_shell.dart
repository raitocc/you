import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';
import '../theme/app_theme.dart';

class AppShell extends StatelessWidget {
  const AppShell({super.key, required this.navigationShell});
  final StatefulNavigationShell navigationShell;
  @override
  Widget build(BuildContext context) {
    const labels = ['瞬间', '时光', '同行', '圈子'];
    const icons = [
      'AiArtIcon',
      'AiBookIcon',
      'AiBackgroundIcon',
      'AppleIntelligenceIcon',
    ];
    Widget tab(int index) => Expanded(
      child: Semantics(
        selected: navigationShell.currentIndex == index,
        child: TextButton(
          style: TextButton.styleFrom(
            foregroundColor: navigationShell.currentIndex == index
                ? Tokens.accent
                : Tokens.muted,
            minimumSize: const Size(48, 56),
          ),
          onPressed: () => navigationShell.goBranch(
            index,
            initialLocation: index == navigationShell.currentIndex,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SvgPicture.asset(
                'assets/icons/${icons[index]}-outline.svg',
                width: 24,
                height: 24,
                colorFilter: ColorFilter.mode(
                  navigationShell.currentIndex == index
                      ? Tokens.accent
                      : Tokens.muted,
                  BlendMode.srcIn,
                ),
              ),
              Text(labels[index], style: const TextStyle(fontSize: 12)),
            ],
          ),
        ),
      ),
    );
    return Scaffold(
      appBar: AppBar(
        toolbarHeight: 76,
        title: const Text(
          'you',
          style: TextStyle(
            fontSize: 38,
            fontWeight: FontWeight.w800,
            letterSpacing: -3,
          ),
        ),
        actions: [
          IconButton(
            tooltip: '个人资料',
            onPressed: () => context.push('/profile'),
            icon: const Icon(Icons.account_circle_outlined),
          ),
        ],
      ),
      body: navigationShell,
      bottomNavigationBar: SafeArea(
        child: Container(
          margin: const EdgeInsets.fromLTRB(12, 8, 12, 12),
          padding: const EdgeInsets.all(4),
          decoration: BoxDecoration(
            border: Border.all(color: Tokens.line),
            borderRadius: BorderRadius.circular(38),
          ),
          child: Row(
            children: [
              tab(0),
              tab(1),
              IconButton.filled(
                tooltip: '发布瞬间',
                onPressed: () => context.push('/moments/new'),
                icon: const Icon(Icons.add),
              ),
              tab(2),
              tab(3),
            ],
          ),
        ),
      ),
    );
  }
}
