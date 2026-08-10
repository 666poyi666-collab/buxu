<#
.SYNOPSIS
    Creates and exercises an Android 11 emulator calibrated to the OWW221 display.

.DESCRIPTION
    This is a framework/UI compatibility environment, not an OPPO firmware emulator.
    It fixes the reproducible parts of the target: API 30, 378x496 portrait display,
    320 dpi, 60 Hz, rotation lock, screen timeout, and app runtime permissions.

    ColorOS Watch, HeyTap HealthKit, the physical sensors, BLE peripheral behavior,
    GNSS accuracy, power management, and rounded OLED clipping still require OWW221.

.EXAMPLE
    .\tools\oww221-avd.ps1 Create
    .\tools\oww221-avd.ps1 Start -Headless
    .\tools\oww221-avd.ps1 Install
    .\tools\oww221-avd.ps1 Verify
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('Create', 'Start', 'Install', 'Verify', 'Sleep', 'Wake', 'Capture', 'Stop')]
    [string]$Action = 'Verify',

    [string]$Name = 'OWW221_API30',
    [int]$Port = 5580,
    [switch]$Headless
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Package = 'system-images;android-30;google_apis;x86_64'
$AppId = 'com.poyi.watchintervals'
$Width = 378
$Height = 496
$Density = 320
$Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\Android\Sdk' }
$Adb = Join-Path $Sdk 'platform-tools\adb.exe'
$Emulator = Join-Path $Sdk 'emulator\emulator.exe'
$AvdManager = Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$AvdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE '.android\avd' }
$AvdDir = Join-Path $AvdHome "$Name.avd"
$Serial = "emulator-$Port"
$EvidenceRoot = Join-Path $PSScriptRoot '..\.gradle\oww221-avd\evidence'

function Assert-Tool([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Required Android SDK tool not found: $Path" }
}

function Invoke-Adb([string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        # adb writes normal diagnostics (notably monkey's argument echo) to stderr.
        # Native process success is therefore determined by its exit code only.
        $ErrorActionPreference = 'Continue'
        $output = & $Adb -s $Serial @Arguments 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) { throw "adb $($Arguments -join ' ') failed: $($output.Trim())" }
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
    throw "$Serial did not finish booting within four minutes."
}

function Set-ConfigValue([string]$Path, [string]$Key, [string]$Value) {
    $lines = if (Test-Path -LiteralPath $Path) { @(Get-Content -LiteralPath $Path) } else { @() }
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

function New-Oww221Avd {
    Assert-Tool $AvdManager
    $imageDir = Join-Path $Sdk 'system-images\android-30\google_apis\x86_64'
    if (-not (Test-Path -LiteralPath (Join-Path $imageDir 'source.properties'))) {
        throw "Missing $Package. Install it with sdkmanager before creating the AVD."
    }

    New-Item -ItemType Directory -Force -Path $AvdHome | Out-Null
    'no' | & $AvdManager create avd --force --name $Name --package $Package --device 'pixel' | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "avdmanager failed to create $Name" }

    $config = Join-Path $AvdDir 'config.ini'
    $values = [ordered]@{
        'AvdId' = $Name
        'avd.ini.displayname' = 'OWW221 Android 11 compatibility'
        'hw.device.name' = 'OWW221 compatibility profile'
        'hw.device.manufacturer' = 'OPPO compatibility profile'
        'hw.device.type' = 'watch'
        'hw.lcd.width' = "$Width"
        'hw.lcd.height' = "$Height"
        'hw.lcd.density' = "$Density"
        'hw.lcd.depth' = '32'
        'hw.gpu.enabled' = 'yes'
        'hw.gpu.mode' = 'auto'
        'hw.keyboard' = 'no'
        'hw.mainKeys' = 'no'
        'hw.gps' = 'yes'
        'hw.accelerometer' = 'yes'
        'hw.gyroscope' = 'yes'
        'hw.sensors.light' = 'yes'
        'hw.sensors.proximity' = 'yes'
        'hw.ramSize' = '2048'
        'disk.dataPartition.size' = '4G'
        'skin.dynamic' = 'yes'
        'skin.name' = "${Width}x${Height}"
        'skin.path' = "${Width}x${Height}"
        'showDeviceFrame' = 'no'
        'PlayStore.enabled' = 'false'
        'tag.display' = 'Google APIs'
        'tag.id' = 'google_apis'
        'image.sysdir.1' = 'system-images\android-30\google_apis\x86_64\'
    }
    foreach ($entry in $values.GetEnumerator()) {
        Set-ConfigValue -Path $config -Key $entry.Key -Value $entry.Value
    }
    Write-Host "Created $Name at $AvdDir"
}

function Start-Oww221Avd {
    Assert-Tool $Emulator
    $args = @('-avd', $Name, '-port', "$Port", '-no-boot-anim', '-no-snapshot-load', '-no-snapshot-save', '-gpu', 'auto')
    if ($Headless) {
        $args += @('-no-window', '-no-audio')
        Start-Process -FilePath $Emulator -ArgumentList $args -WindowStyle Hidden | Out-Null
    } else {
        Start-Process -FilePath $Emulator -ArgumentList $args | Out-Null
    }
    Wait-ForBoot
    Set-Oww221Runtime
}

function Set-Oww221Runtime {
    Wait-ForBoot
    Invoke-Adb @('shell', 'wm', 'size', "${Width}x${Height}") | Out-Null
    Invoke-Adb @('shell', 'wm', 'density', "$Density") | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'system', 'font_scale', '1.0') | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0') | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'system', 'user_rotation', '0') | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'system', 'screen_off_timeout', '30000') | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '0') | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'global', 'window_animation_scale', '1.0') | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'global', 'transition_animation_scale', '1.0') | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1.0') | Out-Null
    Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
    Invoke-Adb @('shell', 'wm', 'dismiss-keyguard') | Out-Null
}

function Install-WatchApp {
    Set-Oww221Runtime
    $apk = Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK not found: $apk" }
    & $Adb -s $Serial install -r -g $apk | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }

    $permissions = @(
        'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_COARSE_LOCATION',
        'android.permission.ACCESS_BACKGROUND_LOCATION',
        'android.permission.ACTIVITY_RECOGNITION',
        'android.permission.BODY_SENSORS'
    )
    foreach ($permission in $permissions) {
        & $Adb -s $Serial shell pm grant $AppId $permission 2>$null | Out-Null
    }
    Invoke-Adb @('shell', 'am', 'force-stop', $AppId) | Out-Null
    Invoke-Adb @('shell', 'monkey', '-p', $AppId, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
    Start-Sleep -Seconds 3
    Write-Host "Installed and launched $AppId on $Serial"
}

function Get-Value([string[]]$Arguments) {
    return (Invoke-Adb $Arguments).Trim()
}

function Test-Oww221Avd {
    Set-Oww221Runtime
    $sdkLevel = Get-Value @('shell', 'getprop', 'ro.build.version.sdk')
    $size = Get-Value @('shell', 'wm', 'size')
    $density = Get-Value @('shell', 'wm', 'density')
    $rotation = Get-Value @('shell', 'settings', 'get', 'system', 'user_rotation')
    $timeout = Get-Value @('shell', 'settings', 'get', 'system', 'screen_off_timeout')
    $version = Get-Value @('shell', 'dumpsys', 'package', $AppId)

    $checks = [ordered]@{
        api30 = $sdkLevel -eq '30'
        display378x496 = $size -match 'Override size: 378x496|Physical size: 378x496'
        density320 = $density -match 'Override density: 320|Physical density: 320'
        portraitLocked = $rotation -eq '0'
        screenTimeout30s = $timeout -eq '30000'
        appInstalled = $version -match 'versionName=0\.22\.0' -and $version -match 'versionCode=33\b'
    }
    $checks.GetEnumerator() | ForEach-Object { '{0}={1}' -f $_.Key, $_.Value }
    if ($checks.Values -contains $false) { throw 'OWW221 AVD verification failed.' }
    Write-Host 'Boundary: generic Google APIs x86_64; no ColorOS Watch, HeyTap HealthKit, physical sensors, BLE peripheral, GNSS accuracy, OLED clipping, or vendor power policy.'
}

function Save-Evidence {
    Test-Oww221Avd
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $dir = Join-Path $EvidenceRoot $stamp
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $remoteScreenshot = '/data/local/tmp/oww221-avd-screen.png'
    Invoke-Adb @('shell', 'screencap', '-p', $remoteScreenshot) | Out-Null
    & $Adb -s $Serial pull $remoteScreenshot (Join-Path $dir 'screen.png') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to pull emulator screenshot.' }
    Invoke-Adb @('shell', 'rm', '-f', $remoteScreenshot) | Out-Null
    @(
        "capturedAt=$(Get-Date -Format o)",
        "serial=$Serial",
        "api=$(Get-Value @('shell', 'getprop', 'ro.build.version.sdk'))",
        "size=$((Get-Value @('shell', 'wm', 'size')) -replace "`r?`n", '; ')",
        "density=$((Get-Value @('shell', 'wm', 'density')) -replace "`r?`n", '; ')",
        "rotation=$(Get-Value @('shell', 'settings', 'get', 'system', 'user_rotation'))",
        'firmwareBoundary=generic Google APIs image; not OWW221 ColorOS Watch firmware'
    ) | Set-Content -LiteralPath (Join-Path $dir 'environment.txt') -Encoding UTF8
    Write-Host "Evidence saved under $dir. Inspect screen.png before sharing; app screens can contain a pairing code."
}

Assert-Tool $Adb
switch ($Action) {
    'Create' { New-Oww221Avd }
    'Start' { Start-Oww221Avd }
    'Install' { Install-WatchApp }
    'Verify' { Test-Oww221Avd }
    'Sleep' { Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_SLEEP') | Out-Null }
    'Wake' {
        Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
        Invoke-Adb @('shell', 'wm', 'dismiss-keyguard') | Out-Null
    }
    'Capture' { Save-Evidence }
    'Stop' { Invoke-Adb @('emu', 'kill') | Out-Null }
}
