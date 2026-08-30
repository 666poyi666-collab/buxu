<#
.SYNOPSIS
    Creates and verifies an isolated API 35 phone emulator for WatchIntervals.

.DESCRIPTION
    The profile exercises Compose layout, system insets, large text, dialogs and local plan
    mutations without touching a physical phone. BLE, OEM background policy and production
    Cloud credentials remain physical-device gates.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('Create', 'Start', 'Install', 'Verify', 'Stop')]
    [string]$Action = 'Verify',

    [string]$Name = 'WatchIntervalsPhone_API35',
    [int]$Port = 5582,
    [switch]$Headless
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Package = 'system-images;android-35;google_apis;x86_64'
$AppId = 'com.poyi.watchintervals.phone'
$Width = 1080
$Height = 2400
$Density = 440
$Serial = "emulator-$Port"
$AvdHome = if ($env:ANDROID_AVD_HOME) {
    $env:ANDROID_AVD_HOME
} else {
    Join-Path $env:USERPROFILE '.android\avd'
}
$AvdDir = Join-Path $AvdHome "$Name.avd"

function Resolve-AndroidSdk {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:ANDROID_SDK_ROOT) { $candidates.Add($env:ANDROID_SDK_ROOT) }
    if ($env:ANDROID_HOME) { $candidates.Add($env:ANDROID_HOME) }
    $properties = Join-Path $ProjectRoot 'local.properties'
    if (Test-Path -LiteralPath $properties) {
        $line = Get-Content -LiteralPath $properties |
                Where-Object { $_ -match '^sdk\.dir=' } |
                Select-Object -First 1
        if ($line) {
            $configured = $line.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
            if ($configured) { $candidates.Add($configured) }
        }
    }
    if ($env:LOCALAPPDATA) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'platform-tools\adb.exe')) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'Android SDK not found.'
}

$Sdk = Resolve-AndroidSdk
$Adb = Join-Path $Sdk 'platform-tools\adb.exe'
$Emulator = Join-Path $Sdk 'emulator\emulator.exe'
$AvdManager = Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat'

function Invoke-Adb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed: $($output.Trim())"
    }
    return $output.Trim()
}

function Wait-ForBoot {
    & $Adb -s $Serial wait-for-device | Out-Null
    $deadline = (Get-Date).AddMinutes(4)
    do {
        $boot = (& $Adb -s $Serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($boot -eq '1') { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$Serial did not finish booting."
}

function Set-ConfigValue([string]$Path, [string]$Key, [string]$Value) {
    $lines = if (Test-Path -LiteralPath $Path) {
        @(Get-Content -LiteralPath $Path)
    } else {
        @()
    }
    $replacement = "$Key=$Value"
    $matched = $false
    $updated = foreach ($line in $lines) {
        if ($line -match "^$([regex]::Escape($Key))=") {
            if (-not $matched) { $replacement }
            $matched = $true
        } else {
            $line
        }
    }
    if (-not $matched) { $updated += $replacement }
    Set-Content -LiteralPath $Path -Value $updated -Encoding ASCII
}

function Expected-Version {
    $source = Get-Content -Raw -LiteralPath (Join-Path $ProjectRoot 'phone\build.gradle')
    $code = [regex]::Match($source, '(?m)^\s*versionCode\s+(\d+)\s*$')
    $name = [regex]::Match($source, "(?m)^\s*versionName\s+'([^']+)'\s*$")
    if (-not $code.Success -or -not $name.Success) {
        throw 'Unable to read Phone version from phone/build.gradle.'
    }
    return [pscustomobject]@{ Code = [int]$code.Groups[1].Value; Name = $name.Groups[1].Value }
}

function Create-Avd {
    $image = Join-Path $Sdk 'system-images\android-35\google_apis\x86_64\source.properties'
    if (-not (Test-Path -LiteralPath $image)) { throw "Missing $Package" }
    'no' | & $AvdManager create avd --force --name $Name --package $Package --device 'pixel_6'
    if ($LASTEXITCODE -ne 0) { throw "Unable to create $Name" }
    $config = Join-Path $AvdDir 'config.ini'
    $values = @{
        'hw.lcd.width' = "$Width"
        'hw.lcd.height' = "$Height"
        'hw.lcd.density' = "$Density"
        'hw.ramSize' = '2048'
        'hw.keyboard' = 'yes'
        'hw.gpu.enabled' = 'yes'
        'hw.gpu.mode' = 'auto'
        'showDeviceFrame' = 'no'
        'image.sysdir.1' = 'system-images\android-35\google_apis\x86_64\'
    }
    foreach ($item in $values.GetEnumerator()) {
        Set-ConfigValue $config $item.Key $item.Value
    }
    Write-Host "Created $Name at $AvdDir"
}

function Set-Runtime {
    Wait-ForBoot
    Invoke-Adb @('shell', 'wm', 'size', "${Width}x${Height}") | Out-Null
    Invoke-Adb @('shell', 'wm', 'density', "$Density") | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'system', 'font_scale', '1.0') | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0') | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'system', 'user_rotation', '0') | Out-Null
    Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
    Invoke-Adb @('shell', 'wm', 'dismiss-keyguard') | Out-Null
    if ((& $Adb -s $Serial shell pm path $AppId 2>$null | Out-String) -match '^package:') {
        Invoke-Adb @('shell', 'am', 'start', '-n', "$AppId/.MainActivity") | Out-Null
        Start-Sleep -Milliseconds 500
    }
}

function Start-Avd {
    $arguments = @(
        '-avd', $Name, '-port', "$Port", '-no-boot-anim',
        '-no-snapshot-load', '-no-snapshot-save', '-gpu', 'auto'
    )
    if ($Headless) {
        $arguments += @('-no-window', '-no-audio')
        Start-Process $Emulator -ArgumentList $arguments -WindowStyle Hidden | Out-Null
    } else {
        Start-Process $Emulator -ArgumentList $arguments | Out-Null
    }
    Set-Runtime
}

function Install-App {
    Set-Runtime
    $apk = Join-Path $ProjectRoot 'phone\build\outputs\apk\debug\phone-debug.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw "Phone APK not found: $apk" }
    & $Adb -s $Serial install -r -g $apk
    if ($LASTEXITCODE -ne 0) { throw 'Phone APK install failed.' }
    Invoke-Adb @('shell', 'am', 'force-stop', $AppId) | Out-Null
    Invoke-Adb @('shell', 'monkey', '-p', $AppId, '1') | Out-Null
    Start-Sleep -Seconds 2
}

function Verify-Avd {
    Set-Runtime
    $expected = Expected-Version
    $package = Invoke-Adb @('shell', 'dumpsys', 'package', $AppId)
    $focus = Invoke-Adb @('shell', 'dumpsys', 'window')
    $checks = [ordered]@{
        api35 = (Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')) -eq '35'
        display1080x2400 = (Invoke-Adb @('shell', 'wm', 'size')) -match '1080x2400'
        density440 = (Invoke-Adb @('shell', 'wm', 'density')) -match '440'
        appInstalled = $package -match ('versionName=' + [regex]::Escape($expected.Name) + '\b') -and
                $package -match ('versionCode=' + $expected.Code + '\b')
        appVisible = $focus -match 'mCurrentFocus=.*com\.poyi\.watchintervals\.phone/.+[A-Za-z]+Activity'
    }
    $checks.GetEnumerator() | ForEach-Object { '{0}={1}' -f $_.Key, $_.Value }
    if ($checks.Values -contains $false) { throw 'Phone AVD verification failed.' }
}

switch ($Action) {
    'Create' { Create-Avd }
    'Start' { Start-Avd }
    'Install' { Install-App }
    'Verify' { Verify-Avd }
    'Stop' { Invoke-Adb @('emu', 'kill') | Out-Null }
}
