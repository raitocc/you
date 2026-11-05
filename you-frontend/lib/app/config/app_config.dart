class AppConfig {
  static const environment = String.fromEnvironment(
    'APP_ENV',
    defaultValue: 'dev',
  );

  static const apiBaseUrl = String.fromEnvironment('API_BASE_URL');

  static bool get isDev => environment == 'dev';
  static bool get isProd => environment == 'prod';

  static void validate() {
    final uri = Uri.tryParse(apiBaseUrl);

    if (uri == null ||
        !uri.hasAuthority ||
        !['http', 'https'].contains(uri.scheme)) {
      throw StateError('API_BASE_URL 配置无效');
    }

    if (isProd && uri.scheme != 'https') {
      throw StateError('生产环境 API 必须使用 HTTPS');
    }
  }
}
