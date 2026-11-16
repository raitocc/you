param([string]$DeviceId, [switch]$SelectDevice)

$ErrorActionPreference = "Stop"

$adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
$adb = if ($adbCommand) {
    $adbCommand.Source
} else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
}
$ports = @(8080, 9000)

# 找出已连接并完成授权的设备，包括真机和模拟器。
$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = $adb
$startInfo.Arguments = 'devices -l'
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardOutput = $true
$process = [System.Diagnostics.Process]::Start($startInfo)
try {
    $devices = $process.StandardOutput.ReadToEnd() -split '\r?\n'
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "Failed to list Android devices using '$adb' (exit $($process.ExitCode))."
    }
} finally {
    $process.Dispose()
}

$serials = @(foreach ($line in $devices) {
    if ($line -match '^\s*(\S+)[\t ]+device(?:\s|$)') {
        $serial = $Matches[1]
        if (-not $DeviceId -or $serial -ceq $DeviceId) {
            $serial
        }
    }
})

if ($serials.Count -eq 0) {
    throw "No matching authorized Android device. ADB path: $adb`n$($devices -join [Environment]::NewLine)"
}
if ($SelectDevice -and $serials.Count -gt 1) {
    throw "Multiple Android devices found. Specify -DeviceId: $($serials -join ', ')"
}

foreach ($serial in $serials) {
    foreach ($port in $ports) {
        & $adb -s $serial reverse "tcp:$port" "tcp:$port" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Port forwarding failed: $serial / $port"
        }
    }
    Write-Host "Forwarded 8080 and 9000 for $serial"
}

if ($SelectDevice) {
    $serials[0]
}
