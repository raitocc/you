$ErrorActionPreference = "Stop"

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ports = @(8080, 9000)

# 找出已连接并完成授权的设备，包括真机和模拟器。
$devices = & $adb devices
if ($LASTEXITCODE -ne 0) {
    throw "Failed to list Android devices."
}

foreach ($line in $devices) {
    if ($line -match '^(\S+)\s+device$') {
        $serial = $Matches[1]

        foreach ($port in $ports) {
            & $adb -s $serial reverse "tcp:$port" "tcp:$port"
            if ($LASTEXITCODE -ne 0) {
                throw "Port forwarding failed: $serial / $port"
            }
        }

        Write-Host "Forwarded 8080 and 9000 for $serial"
    }
}