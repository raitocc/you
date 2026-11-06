import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:you_app/features/moment/presentation/widgets/moment_editor_sheets.dart';

void main() {
  for (final (label, open)
      in <(String, Future<List<String>?> Function(BuildContext))>[
        ('标签', (context) => showMomentTags(context, ['旅行'])),
        (
          '可见范围',
          (context) => showMomentTargets(
            context,
            journey: false,
            selected: [],
            known: [],
          ),
        ),
      ]) {
    testWidgets('$label keeps its controller alive through dismissal', (
      tester,
    ) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => open(context),
                child: const Text('打开'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'test');
      await tester.tap(find.text('完成'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 16));

      final controller = tester
          .widget<TextField>(find.byType(TextField))
          .controller!;
      void listener() {}
      expect(() => controller.addListener(listener), returnsNormally);
      controller.removeListener(listener);

      await tester.pumpAndSettle();
      expect(find.byType(TextField), findsNothing);
      expect(
        () => controller.addListener(listener),
        throwsA(isA<FlutterError>()),
      );
      expect(tester.takeException(), isNull);
    });
  }
}
