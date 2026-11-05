param([string]$ApiBaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    if (Test-Path 'build/api-live-users.json') { throw '先运行 python tool/cleanup_api_fixtures.py 清理上次测试数据' }
    & flutter test '--dart-define=RUN_API_TESTS=true' "--dart-define=API_BASE_URL=$ApiBaseUrl"
    $testResult = $LASTEXITCODE
    if (Test-Path 'build/api-live-users.json') {
        & python tool/cleanup_api_fixtures.py
        if ($LASTEXITCODE -ne 0) { throw '测试数据清理失败，请检查后重新清理' }
    }
    if ($testResult -ne 0) { throw "接口测试失败，退出码 $testResult" }
} finally { Pop-Location }
