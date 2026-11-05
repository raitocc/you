import 'package:flutter/material.dart';

abstract final class Tokens {
  static const ink = Color(0xFF202421),
      muted = Color(0xFF687068),
      line = Color(0xFFEDF0EB),
      green = Color(0xFFE8EDDF),
      accent = Color(0xFF4F6247);
  static const pagePadding = 18.0, radius = 24.0;
}

abstract final class AppTheme {
  static ThemeData get light => ThemeData(
    useMaterial3: true,
    scaffoldBackgroundColor: Colors.white,
    fontFamily: 'SF Pro',
    fontFamilyFallback: const ['PingFang SC', 'sans-serif'],
    colorScheme: ColorScheme.fromSeed(
      seedColor: Tokens.accent,
      surface: Colors.white,
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: Colors.white,
      foregroundColor: Tokens.ink,
      elevation: 0,
      scrolledUnderElevation: 0,
    ),
    dividerColor: Tokens.line,
    inputDecorationTheme: const InputDecorationTheme(
      border: OutlineInputBorder(),
      contentPadding: EdgeInsets.all(16),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: Tokens.ink,
        minimumSize: const Size(48, 48),
      ),
    ),
  );
}
