class AppException implements Exception {
  const AppException(this.code, this.message);
  final String code, message;
  @override
  String toString() => message;
}
