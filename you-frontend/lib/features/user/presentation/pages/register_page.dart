import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../../core/network/api_client.dart';
import '../../data/user_repository.dart';

class RegisterPage extends ConsumerStatefulWidget {
  const RegisterPage({super.key});
  @override
  ConsumerState<RegisterPage> createState() => _RegisterPageState();
}

class _RegisterPageState extends ConsumerState<RegisterPage> {
  final username = TextEditingController(),
      password = TextEditingController(),
      nickname = TextEditingController();
  bool busy = false;
  String? error;
  @override
  void dispose() {
    username.dispose();
    password.dispose();
    nickname.dispose();
    super.dispose();
  }

  Future<void> submit() async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await ref
          .read(userRepositoryProvider)
          .register(username.text, password.text, nickname.text);
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('注册成功，请登录')));
      if (context.canPop()) {
        context.pop();
      } else {
        context.go('/login');
      }
    } catch (e) {
      if (mounted) setState(() => error = errorMessage(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('注册')),
    body: ListView(
      padding: const EdgeInsets.all(28),
      children: [
        TextField(
          controller: username,
          enabled: !busy,
          decoration: const InputDecoration(
            labelText: '用户名',
            helperText: '字母开头，4～64 位字母、数字或下划线',
          ),
        ),
        const SizedBox(height: 16),
        TextField(
          controller: password,
          enabled: !busy,
          obscureText: true,
          decoration: const InputDecoration(
            labelText: '密码',
            helperText: '8～64 字符，包含字母和数字',
          ),
        ),
        const SizedBox(height: 16),
        TextField(
          controller: nickname,
          enabled: !busy,
          maxLength: 64,
          decoration: const InputDecoration(labelText: '昵称'),
        ),
        const SizedBox(height: 24),
        if (error != null) Text(error!),
        FilledButton(
          onPressed: busy ? null : submit,
          child: Text(busy ? '注册中…' : '注册'),
        ),
      ],
    ),
  );
}
