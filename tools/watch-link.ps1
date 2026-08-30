<#
.SYNOPSIS
    Keeps the OWW221 development watch reachable over network ADB and keeps its screen awake.

.DESCRIPTION
    OWW221 runs unrooted, so `persist.adb.tcp.port` cannot be written and TCP ADB does not
    survive a watch reboot on its own. This script re-establishes the link idempotently:

      1. Find the watch on USB (or an already-connected TCP endpoint).
      2. Read its current wlan0 address instead of trusting a hard-coded IP.
      3. Re-arm `adb tcpip` and connect.
      4. Re-apply the development display policy (max screen timeout, stay-on while charging,
         Wi-Fi never sleeps) so the watch stays debuggable and mDNS keeps answering.

    Safe to run repeatedly; every step is a no-op when already in the desired state.
    Register it with -Install to run at logon and every few minutes.

.PARAMETER Install
    Register the recurring scheduled task instead of running a single pass.

.PARAMETER Uninstall
    Remove the scheduled task.

.PARAMETER IntervalMinutes
    Repetition interval for the scheduled task. Default 5.

.PARAMETER NoDisplayPolicy
    Only restore connectivity; leave screen timeout and stay-on untouched.
#>
[CmdletBinding(DefaultParameterSetName = 'Run')]
param(
    [Parameter(ParameterSetName = 'Install')][switch]$Install,
    [Parameter(ParameterSetName = 'Uninstall')][switch]$Uninstall,
    [Parameter(ParameterSetName = 'Install')][int]$IntervalMinutes = 5,
    [Parameter(ParameterSetName = 'Run')][switch]$NoDisplayPolicy
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$TaskName = 'PoyiWatchAdbLink'
$AdbPort = 5555
# Product string reported by the OPPO Watch 4 Pro dev unit; keeps other phones/emulators out.
$WatchProduct = 'OWW221'
$StateDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) '.work'
$EndpointState = Join-Path $StateDirectory 'watch-adb-endpoint.txt'

function Get-Adb {
    # @(...) around the pipeline result: a single match would otherwise index as a char.
    $candidates = @(@(
        $env:ADB,
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ProgramFiles\Android\Sdk\platform-tools\adb.exe",
        'C:\Android\Sdk\platform-tools\adb.exe'
    ) | Where-Object { $_ -and (Test-Path $_) })
    if ($candidates.Count -gt 0) { return $candidates[0] }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw 'adb.exe not found. Set $env:ADB or install Android platform-tools.'
}

$Adb = Get-Adb

function Invoke-Adb {
    param([string[]]$Arguments)
    # 2>&1 keeps adb's stderr diagnostics in the returned text instead of the PowerShell error stream.
    $output = & $Adb @Arguments 2>&1 | Out-String
    return $output.Trim()
}

function Get-Devices {
    $lines = (Invoke-Adb @('devices', '-l')) -split "`r?`n" | Select-Object -Skip 1
    foreach ($line in $lines) {
        if ($line -notmatch '^\s*(\S+)\s+(device|offline)\b') { continue }
        $serial = $Matches[1]
        $state = $Matches[2]
        $product = if ($line -match 'product:(\S+)') { $Matches[1] } else { '' }
        [pscustomobject]@{
            Serial    = $serial
            State     = $state
            Product   = $product
            IsNetwork = $serial -match ':\d+$'
        }
    }
}

function Get-WatchIp {
    param([string]$Serial)
    $output = Invoke-Adb @('-s', $Serial, 'shell', 'ip -f inet addr show wlan0')
    if ($output -match 'inet\s+(\d+\.\d+\.\d+\.\d+)') { return $Matches[1] }
    return $null
}

function Set-DisplayPolicy {
    param([string]$Serial)
    # Max int32 timeout + stay-on-while-charging keeps the panel up for UI work and ADB alive.
    Invoke-Adb @('-s', $Serial, 'shell', 'settings put system screen_off_timeout 2147483647') | Out-Null
    Invoke-Adb @('-s', $Serial, 'shell', 'svc power stayon true') | Out-Null
    # wifi_sleep_policy=2 (never sleep) keeps the 8765 API and mDNS answering with the screen off.
    Invoke-Adb @('-s', $Serial, 'shell', 'settings put global wifi_sleep_policy 2') | Out-Null
}

function Get-RememberedEndpoint {
    if (-not (Test-Path $EndpointState)) { return $null }
    $value = ([System.IO.File]::ReadAllText($EndpointState)).Trim()
    if ($value -match '^\d+\.\d+\.\d+\.\d+:5555$') { return $value }
    return $null
}

function Save-RememberedEndpoint {
    param([string]$Endpoint)
    New-Item -ItemType Directory -Path $StateDirectory -Force | Out-Null
    [System.IO.File]::WriteAllText($EndpointState, $Endpoint + [Environment]::NewLine)
}

function Invoke-LinkPass {
    Invoke-Adb @('start-server') | Out-Null
    $devices = @(Get-Devices)
    $watches = @($devices | Where-Object {
        $_.Product -eq $WatchProduct -and $_.State -eq 'device'
    })
    $offlineWatch = $devices | Where-Object {
        $_.Product -eq $WatchProduct -and $_.State -eq 'offline' -and $_.IsNetwork
    } | Select-Object -First 1

    $usb = $watches | Where-Object { -not $_.IsNetwork } | Select-Object -First 1
    $net = $watches | Where-Object { $_.IsNetwork } | Select-Object -First 1

    $ip = $null
    if ($usb) {
        if (-not $NoDisplayPolicy) { Set-DisplayPolicy -Serial $usb.Serial }
        Invoke-Adb @('-s', $usb.Serial, 'shell', 'svc wifi enable') | Out-Null
        for ($attempt = 0; $attempt -lt 10 -and -not $ip; $attempt++) {
            $ip = Get-WatchIp -Serial $usb.Serial
            if (-not $ip) { Start-Sleep -Seconds 2 }
        }
        if ($ip) {
            # Re-arming tcpip is harmless when the daemon is already in TCP mode.
            Invoke-Adb @('-s', $usb.Serial, 'tcpip', "$AdbPort") | Out-Null
            Start-Sleep -Seconds 2
        }
    } elseif ($net) {
        $ip = ($net.Serial -split ':')[0]
    } elseif ($offlineWatch) {
        # An offline transport remains in `adb devices` after Wi-Fi sleeps. Keeping that row out
        # of the candidate set meant the old script never issued the reconnect that could recover
        # it. Drop the stale transport and redial the same verified OWW221 endpoint.
        $ip = ($offlineWatch.Serial -split ':')[0]
        Invoke-Adb @('disconnect', $offlineWatch.Serial) | Out-Null
    } else {
        $remembered = Get-RememberedEndpoint
        if ($remembered) { $ip = ($remembered -split ':')[0] }
    }

    if (-not $ip) {
        if ($usb) {
            Write-Host 'watch-link: OWW221 USB ADB online; Wi-Fi has no address yet, will retry.'
            return $true
        }
        Write-Host 'watch-link: no OWW221 reachable on USB or TCP; nothing to do.'
        return $false
    }

    $endpoint = "${ip}:$AdbPort"
    $connect = Invoke-Adb @('connect', $endpoint)
    if ($connect -notmatch 'connected to') {
        Write-Host "watch-link: connect failed for $endpoint -> $connect"
        return $false
    }

    $model = Invoke-Adb @('-s', $endpoint, 'shell', 'getprop ro.product.model')
    if ($model -ne $WatchProduct) {
        Invoke-Adb @('disconnect', $endpoint) | Out-Null
        Write-Host "watch-link: rejected $endpoint because model '$model' is not $WatchProduct."
        return $false
    }

    Save-RememberedEndpoint -Endpoint $endpoint

    if (-not $NoDisplayPolicy) { Set-DisplayPolicy -Serial $endpoint }

    Write-Host "watch-link: $endpoint online$(if (-not $NoDisplayPolicy) { ' (display policy applied)' })."
    return $true
}

function Install-Task {
    param([int]$Minutes)
    # schtasks.exe registers in the caller's own context; Register-ScheduledTask needs elevation
    # to write the root task folder, which this workstation denies for the interactive user.
    $run = "powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    $result = & schtasks.exe /Create /TN $TaskName /SC MINUTE /MO $Minutes /F /TR $run 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "schtasks failed: $($result.Trim())" }
    Write-Host "watch-link: scheduled task '$TaskName' installed (every $Minutes min)."
}

function Uninstall-Task {
    & schtasks.exe /Delete /TN $TaskName /F 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { Write-Host "watch-link: scheduled task '$TaskName' removed." }
    else { Write-Host "watch-link: scheduled task '$TaskName' was not registered." }
}

switch ($PSCmdlet.ParameterSetName) {
    'Install' { Install-Task -Minutes $IntervalMinutes; Invoke-LinkPass | Out-Null }
    'Uninstall' { Uninstall-Task }
    default { if (-not (Invoke-LinkPass)) { exit 1 } }
}
