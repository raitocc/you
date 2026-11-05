import 'package:flutter/material.dart';
import '../../../../shared/ui/page_state.dart';

class MomentPage extends StatelessWidget {
  const MomentPage({super.key});
  @override
  Widget build(BuildContext context) => const DefaultTabController(
    length: 2,
    child: Column(
      children: [
        TabBar(
          isScrollable: true,
          tabAlignment: TabAlignment.center,
          dividerHeight: 0,
          tabs: [
            Tab(text: '你'),
            Tab(text: '与你有关'),
          ],
        ),
        Expanded(
          child: TabBarView(
            children: [
              PageState(title: '留住此刻', message: '瞬间列表将在接口接入后显示'),
              PageState(title: '与你有关', message: '同行与圈子中的记录将在这里相遇'),
            ],
          ),
        ),
      ],
    ),
  );
}
