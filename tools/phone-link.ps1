<#
.SYNOPSIS
    Keeps one explicitly identified Android phone reachable over Wi-Fi ADB.

.DESCRIPTION
    Reuses an existing authorized ADB-over-TCP service. The caller supplies the private mDNS
    instance name through the scheduled-task arguments, so no personal device identifier is
    stored in the repository. After connecting, the script verifies ro.product.device before
    applying keepalive settings.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._-]{1,128}$')]
    [string]$ServiceName,

    [ValidatePattern('^[A-Za-z0-9._-]{1,64}$')]
    [string]$ExpectedDevice = 'xaga'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Adb {
    $candidates = @(@(
        $env:ADB,
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ProgramFiles\Android\Sdk\platform-tools\adb.exe",
        'C:\Android\Sdk\platform-tools\adb.exe'
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) })
    if ($candidates.Count -gt 0) { return $candidates[0] }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw 'adb.exe not found. Set the user ADB environment variable or install platform-tools.'
}

$Adb = Get-Adb
& $Adb start-server | Out-Null

$connected = & $Adb devices -l
if ($connected -match "\s+device\s+.*(?:product|device):$([regex]::Escape($ExpectedDevice))\b") {
    Write-Host "phone-link: $ExpectedDevice already connected."
    exit 0
}

$service = & $Adb mdns services |
    Where-Object { $_ -match "^$([regex]::Escape($ServiceName))\s+_adb\._tcp\s+(\S+)$" } |
    Select-Object -First 1
if (-not $service -or $service -notmatch '(\d+\.\d+\.\d+\.\d+:\d+)$') {
    Write-Host "phone-link: mDNS service $ServiceName is unavailable."
    exit 1
}

$endpoint = $Matches[1]
& $Adb connect $endpoint | Out-Null
$device = (& $Adb -s $endpoint shell getprop ro.product.device 2>$null | Out-String).Trim()
if ($device -ne $ExpectedDevice) {
    & $Adb disconnect $endpoint | Out-Null
    Write-Host "phone-link: endpoint identity mismatch."
    exit 2
}

& $Adb -s $endpoint shell settings put global adb_enabled 1 | Out-Null
& $Adb -s $endpoint shell settings put global wifi_sleep_policy 2 | Out-Null
Write-Host "phone-link: $endpoint online."
exit 0
