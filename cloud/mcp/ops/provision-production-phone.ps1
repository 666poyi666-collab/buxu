param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [Parameter(Mandatory = $true)]
    [switch]$ConfirmProductionSwitch
)

$ErrorActionPreference = 'Stop'
if (-not $ConfirmProductionSwitch) {
    throw 'Production provisioning requires -ConfirmProductionSwitch.'
}
if ($Serial -notmatch '^[A-Za-z0-9._:-]{1,128}$') {
    throw 'Invalid ADB serial.'
}

$root = Split-Path -Parent $PSScriptRoot
$wrangler = Join-Path $root 'node_modules\wrangler\bin\wrangler.js'
$config = Join-Path $root 'wrangler.jsonc'
$adbCandidates = @(
    (Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source),
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
if (@($adbCandidates).Count -eq 0) { throw 'Android platform-tools adb.exe was not found.' }
$adb = [string](@($adbCandidates | Select-Object -First 1)[0])
# This custom domain is routed to the production Worker and is the reachable production
# exchange host for phones whose network path times out on workers.dev.
$productionExchangeEndpoint = 'https://watch-staging.pyzzgk.dpdns.org/sync/v3/exchange'
$deviceId = 'watch-prod-' + [guid]::NewGuid().ToString()
$random = [byte[]]::new(32)
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($random)
$suffix = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$token = 'dw1.' + $deviceId + '.' + $suffix
$sha = [Security.Cryptography.SHA256]::Create()
$registered = $false

try {
    $tokenHash = [BitConverter]::ToString(
        $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($token))
    ).Replace('-', '').ToLowerInvariant()
    $createdAt = [DateTime]::UtcNow.ToString('o')
    $sql = "INSERT INTO sync_devices " +
        "(device_id,label,token_hash,created_at,revoked_at,last_successful_exchange_at," +
        "last_successful_push_at,last_successful_pull_at,last_cursor) VALUES " +
        "('$deviceId','OWW221 production phone bridge','$tokenHash','$createdAt'," +
        "NULL,NULL,NULL,NULL,NULL)"

    & node $wrangler d1 execute watch-mcp --remote --config $config --command $sql *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Production device registration failed.' }
    $registered = $true

    & $adb -s $Serial shell am start -S `
        -n 'com.poyi.watchintervals.phone/.MainActivity' `
        --es poyi_cloud_endpoint $productionExchangeEndpoint `
        --es poyi_cloud_key $token *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Phone provisioning failed.' }

    [pscustomobject]@{
        ok = $true
        registered = $true
        phoneProvisioned = $true
        credentialsExposed = $false
    } | ConvertTo-Json -Compress
} catch {
    if ($registered) {
        $cleanup = "DELETE FROM sync_devices WHERE device_id='$deviceId'"
        & node $wrangler d1 execute watch-mcp --remote --config $config --command $cleanup *> $null
    }
    throw
} finally {
    $rng.Dispose()
    $sha.Dispose()
    [Array]::Clear($random, 0, $random.Length)
    $token = $null
    $suffix = $null
    $tokenHash = $null
}
