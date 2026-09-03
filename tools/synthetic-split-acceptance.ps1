param(
    [ValidateSet('Inject', 'Cleanup')]
    [string]$Action = 'Inject',
    [Parameter(Mandatory = $true)]
    [string]$WatchSerial,
    [string]$RecordId = 'codex-synthetic-split-prod-20260730'
)

$ErrorActionPreference = 'Stop'
if ($WatchSerial -notmatch '^[A-Za-z0-9._:-]{1,128}$') { throw 'Invalid watch serial.' }
if ($RecordId -notmatch '^codex-synthetic-split-[A-Za-z0-9._-]{1,120}$') {
    throw 'Synthetic record id is outside the removable acceptance-test namespace.'
}

$package = 'com.poyi.watchintervals'
$directory = "files/workouts/$RecordId"

function Invoke-WatchShell([string]$Command) {
    if ($Command.Contains("'")) { throw 'Remote command must not contain single quotes.' }
    $remote = "run-as $package sh -c '$Command'"
    & adb.exe -s $WatchSerial shell $remote
    if ($LASTEXITCODE -ne 0) { throw "Watch command failed: $Command" }
}

function Write-WatchFile([string]$Path, [string]$Value) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
    Invoke-WatchShell "echo $encoded | base64 -d > $Path"
}

function Invoke-AdbQuiet([string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & adb.exe @Arguments *> $null
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) { throw 'ADB command failed.' }
}

if ($Action -eq 'Cleanup') {
    Invoke-WatchShell "rm -f $directory/summary.json $directory/route.ndjson $directory/heart.ndjson"
    Invoke-WatchShell "rmdir $directory"
    Invoke-AdbQuiet @('-s', $WatchSerial, 'shell', 'am', 'start', '-n', "$package/.MainActivity")
    [pscustomobject]@{ ok = $true; action = 'cleanup'; recordId = $RecordId; systemLayer = 'not_written' } |
        ConvertTo-Json -Compress
    exit 0
}

& adb.exe -s $WatchSerial shell run-as $package test ! -e $directory
if ($LASTEXITCODE -ne 0) { throw 'Synthetic record already exists; clean it before injecting again.' }
Invoke-WatchShell "mkdir $directory"

$endedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$startedAt = $endedAt - 1200000
$splits = @(
    [ordered]@{ index = 1; distanceMeters = 1000; durationMs = 300000; paceSecondsPerKm = 300 },
    [ordered]@{ index = 2; distanceMeters = 200; durationMs = 60000; paceSecondsPerKm = 300 }
)
$summary = [ordered]@{
    schemaVersion = 3
    id = $RecordId
    startedAt = $startedAt
    endedAt = $endedAt
    durationMs = 1200000
    pausedDurationMs = 0
    elapsedDurationMs = 1200000
    distanceMeters = 1200
    steps = 10000
    calories = 500
    synthetic = $true
    stepTimeline = @(
        [ordered]@{ elapsedMs = 600000; steps = 7000 },
        [ordered]@{ elapsedMs = 1200000; steps = 10000 }
    )
    averageHeartRate = 150
    plan = ''
    planName = '合成公里验收（非真实训练）'
    planGroup = '系统验收'
    planRequirement = '只验证手表历史、正式云同步和 MCP 分段回读。'
    planCompletedActiveMs = 0
    planCompletedWallTime = 0
    freeRecordingActiveMs = 0
    planDistanceMeters = 1200
    freeRecordingDistanceMeters = 0
    maxSmoothedSpeedMps = 3.6
    routePointCount = 0
    stageResults = @()
    averagePaceSecondsPerKm = 300
    averageCadenceSpm = 250
    elevationGainMeters = 0
    splits = $splits
    bestPaceSecondsPerKm = 300
    heartRateRange = [ordered]@{ min = 140; max = 160 }
    dataSourceSummary = [ordered]@{
        distanceSource = 'synthetic_acceptance'
        speedSource = 'synthetic_acceptance'
        heartRateSource = 'synthetic_acceptance'
        locationAccuracyClass = 'synthetic_acceptance'
    }
}

Write-WatchFile "$directory/summary.json" ($summary | ConvertTo-Json -Compress -Depth 10)
Write-WatchFile "$directory/route.ndjson" ''
Write-WatchFile "$directory/heart.ndjson" ''
Invoke-AdbQuiet @('-s', $WatchSerial, 'shell', 'am', 'start', '-n', "$package/.MainActivity")

[pscustomobject]@{
    ok = $true
    action = 'inject'
    recordId = $RecordId
    distanceMeters = 1200
    splitCount = 2
    synthetic = $true
    systemLayer = 'not_written'
    systemLayerReason = 'private app history fixture; OEM HealthKit exercise session is not importable'
} | ConvertTo-Json -Compress
