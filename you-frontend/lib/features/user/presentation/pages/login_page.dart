import 'package:go_router/go_router.dart';
import '../../../../core/network/api_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../viewmodels/login_viewmodel.dart';

// View 层：展示 UI、收集用户输入、响应用户操作。
class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});
  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final username = TextEditingController(), password = TextEditingController();
  @override
  void dispose() {
    username.dispose();
    password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(loginProvider);
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(28),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: AutofillGroup(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text(
                      'you',
                      style: TextStyle(
                        fontSize: 56,
                        fontWeight: FontWeight.w800,
                        letterSpacing: -4,
                      ),
                    ),
                    const Text('把日子留给自己'),
                    const SizedBox(height: 40),
                    TextField(
                      controller: username,
                      autofillHints: const [AutofillHints.username],
                      decoration: const InputDecoration(labelText: '用户名'),
                    ),
                    const SizedBox(height: 16),
                    TextField(
                      controller: password,
                      obscureText: true,
                      autofillHints: const [AutofillHints.password],
                      decoration: const InputDecoration(labelText: '密码'),
                    ),
                    const SizedBox(height: 24),
                    if (state.hasError)
                      Padding(
                        padding: EdgeInsets.only(bottom: 16),
                        child: Text(
                          errorMessage(state.error),
                          semanticsLabel: '登录失败',
                        ),
                      ),
                    FilledButton(
                      onPressed: state.isLoading
                          ? null
                          : () => ref
                                .read(loginProvider.notifier)
                                .submit(username.text.trim(), password.text),
                      child: Text(state.isLoading ? '登录中…' : '登录'),
                    ),
                    TextButton(
                      onPressed: state.isLoading
                          ? null
                          : () => context.push('/register'),
                      child: const Text('创建账号'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
