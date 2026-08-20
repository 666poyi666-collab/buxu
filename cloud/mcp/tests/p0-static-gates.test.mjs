import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import { dirname, relative, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const FIXTURE_PATH = resolve(ROOT, 'contracts', 'watch-sync-v1.fixture.json')
const MANIFEST_PATH = resolve(ROOT, '..', '..', '.poyi', 'project-platform.json')
const PRODUCTION_CONFIG_PATH = resolve(ROOT, 'wrangler.jsonc')
const STAGING_CONFIG_PATH = resolve(ROOT, 'wrangler.staging.jsonc')
const PACKAGE_PATH = resolve(ROOT, 'package.json')
const REQUIRED_FILES = [
  'contracts/watch-sync-v1.fixture.json',
  'contracts/watch-sync-v1.schema.json',
  'contracts/watch-authority-observation-v1.schema.json',
  'migrations/0001_sync_v1.sql',
  'migrations/0003_watch_read_projection.sql',
  'migrations/0004_watch_read_projection_state.sql',
  'migrations/0005_authority_observation.sql',
  'ops/authority-checkpoint-triggers.sql',
  'ops/staging-mcp-probe.mjs',
  'ops/provision-production-phone.ps1',
  'schema.sql',
  'src/index.ts',
  'src/authority-observation.ts',
  'src/oauth.ts',
  'src/sync.ts',
  'tests/d1-schema.test.mjs',
  'tests/contract-schema.test.mjs',
  'tests/worker-contract.test.mjs',
]
const fixtureText = await readFile(FIXTURE_PATH, 'utf8')
const fixture = JSON.parse(fixtureText)
const manifest = JSON.parse(await readFile(MANIFEST_PATH, 'utf8'))
const productionConfigText = await readFile(PRODUCTION_CONFIG_PATH, 'utf8')
const stagingConfigText = await readFile(STAGING_CONFIG_PATH, 'utf8')
const packageJson = JSON.parse(await readFile(PACKAGE_PATH, 'utf8'))

function objectKeys(value) {
  assert.ok(value && typeof value === 'object' && !Array.isArray(value))
  return Object.keys(value).sort()
}

function visit(value, path = '$', findings = []) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => visit(item, `${path}[${index}]`, findings))
    return findings
  }
  if (!value || typeof value !== 'object') return findings
  for (const [key, child] of Object.entries(value)) {
    if (/^(?:authorization|credentialId|deviceToken|operations|syncKey|token)$/i.test(key)) {
      findings.push(`${path}.${key}`)
    }
    visit(child, `${path}.${key}`, findings)
  }
  return findings
}

async function findAndroidArtifacts(directory, results = []) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (entry.isDirectory() && ['.git', '.wrangler', 'node_modules'].includes(entry.name)) continue
    const absolute = resolve(directory, entry.name)
    if (entry.isDirectory()) {
      await findAndroidArtifacts(absolute, results)
      continue
    }
    if (
      entry.name === 'AndroidManifest.xml' ||
      /^(?:build\.gradle(?:\.kts)?|CloudSyncCredentials\.(?:java|kt)|CloudSyncEngine\.(?:java|kt))$/.test(entry.name) ||
      /\.(?:java|kt)$/.test(entry.name)
    ) {
      results.push(relative(ROOT, absolute))
    }
  }
  return results
}

test('canonical fixture is the only credential-free wire shape', () => {
  assert.deepEqual(objectKeys(fixture.request), ['cursor', 'deviceId', 'mutations', 'protocolVersion'])
  assert.ok(fixture.request.mutations.length > 0)
  for (const mutation of fixture.request.mutations) {
    assert.deepEqual(objectKeys(mutation), ['baseRevision', 'entityId', 'entityType', 'opId', 'operation', 'payload'])
    if (mutation.entityType === 'plan') {
      assert.deepEqual(objectKeys(mutation.payload), [
        'groupId', 'groupName', 'groupSortOrder', 'id', 'name', 'requirement', 'schemaVersion', 'selected', 'stages', 'updatedAt',
      ])
      assert.equal(mutation.payload.id, mutation.entityId)
    }
  }
  assert.deepEqual(visit(fixture.request), [], 'credential or obsolete wire field leaked into canonical fixture')
  assert.doesNotMatch(fixtureText, /Bearer\s+/i)
})

test('contract response fixture carries audit and conflict-preservation fields', () => {
  assert.deepEqual(objectKeys(fixture.response.acknowledged[0]), [
    'entityId', 'entityType', 'opId', 'operation', 'outcome', 'revision',
  ])
  assert.ok(fixture.response.changes.every((change) => Number.isSafeInteger(change.sequence)))
  assert.ok(fixture.response.changes.every((change) => change.entityType === 'plan' || change.entityType === 'workout'))
})

test('all contract, migration, source and black-box gate files exist', async () => {
  await Promise.all(REQUIRED_FILES.map(async (path) => {
    const contents = await readFile(resolve(ROOT, path))
    assert.ok(contents.byteLength > 0, `${path} must not be empty`)
  }))
})

test('project platform manifest declares canonical routes without secrets', () => {
  assert.equal(manifest.standardVersion, 1)
  assert.deepEqual(manifest.mcp.routes, {
    mcp: '/mcp',
    health: '/healthz',
    ready: '/readyz',
    oauthResourceMetadata: '/.well-known/oauth-protected-resource/mcp',
  })
  assert.deepEqual(manifest.sync.routes, {
    exchange: '/sync/v2/exchange',
    status: '/sync/v2/status',
  })
  assert.equal(manifest.mcp.status, 'partial')
  assert.equal(manifest.sync.status, 'partial')
  assert.equal(manifest.sync.supportsPcOff, false)
  assert.equal(manifest.sync.supportsBidirectionalDelta, false)
  assert.deepEqual(visit(manifest), [], 'manifest must not contain credential values or credential-shaped fields')
})

test('staging declares the exact canonical authority audience without a capability value', () => {
  assert.match(stagingConfigText,
    /"WATCH_AUTHORITY_AUDIENCE"\s*:\s*"https:\/\/personal-mcp-authority-staging\.focuslink-poyi-6465e9\.workers\.dev\/authority\/watch"/)
  assert.doesNotMatch(stagingConfigText, /"WATCH_AUTHORITY_CAPABILITY"\s*:/)
  assert.match(stagingConfigText,
    /"WATCH_PLAN_REVISION_DOMAIN_ID"\s*:\s*"v3d\.watch-staging-owner-v1"/)
  assert.doesNotMatch(stagingConfigText, /"custom_domain"\s*:\s*true/)
})

test('production declares exact OAuth and authority resources without capability values', () => {
  assert.match(productionConfigText,
    /"pattern"\s*:\s*"watch-staging\.pyzzgk\.dpdns\.org"\s*,\s*"custom_domain"\s*:\s*true/)
  assert.match(productionConfigText,
    /"BUILD_COMMIT"\s*:\s*"396f57915d308d61f0106cdb93b9375c01f6da84"/)
  assert.match(productionConfigText,
    /"OAUTH_AUDIENCE"\s*:\s*"https:\/\/watch-mcp\.focuslink-poyi-6465e9\.workers\.dev\/mcp"/)
  assert.match(productionConfigText,
    /"OAUTH_RS_CLIENT_ID"\s*:\s*"watch-cloud-mcp"/)
  assert.match(productionConfigText,
    /"WATCH_PLAN_REVISION_DOMAIN_ID"\s*:\s*"v3d\.watch-production-owner-v1"/)
  assert.doesNotMatch(productionConfigText, /v3d\.watch-staging-owner-v1/)
  assert.match(productionConfigText,
    /"WATCH_AUTHORITY_AUDIENCE"\s*:\s*"https:\/\/personal-mcp-authority\.focuslink-poyi-6465e9\.workers\.dev\/authority\/watch"/)
  assert.match(productionConfigText,
    /"binding"\s*:\s*"OAUTH_HTTP"\s*,\s*"service"\s*:\s*"poyi-oauth-as"/)
  assert.doesNotMatch(productionConfigText, /"(?:OAUTH_RS_CLIENT_SECRET|WATCH_AUTHORITY_CAPABILITY|SYNC_KEY)"\s*:/)
})

test('production phone provisioning keeps the device token out of output and persists only its hash', async () => {
  const script = await readFile(resolve(ROOT, 'ops', 'provision-production-phone.ps1'), 'utf8')
  assert.match(script, /ConfirmProductionSwitch/)
  assert.match(script, /SHA256/)
  assert.match(script, /token_hash/)
  assert.match(script, /https:\/\/watch-staging\.pyzzgk\.dpdns\.org\/sync\/v3\/exchange/)
  assert.match(script, /credentialsExposed\s*=\s*\$false/)
  assert.match(script, /\[Array\]::Clear/)
  assert.doesNotMatch(script, /Write-(?:Host|Output).*(?:\$token|\$suffix)/i)
})

test('staging MCP acceptance is a checked-in non-empty read-only gate', async () => {
  assert.equal(packageJson.scripts['test:staging:mcp'], 'node ops/staging-mcp-probe.mjs')
  const probe = await readFile(resolve(ROOT, 'ops', 'staging-mcp-probe.mjs'), 'utf8')
  assert.match(probe, /WATCH_OAUTH_ACCESS_TOKEN/)
  assert.match(probe, /watch_list_plans/)
  assert.match(probe, /watch_list_workouts/)
  assert.match(probe, /watch_summarize_workouts/)
  assert.match(probe, /plans\.plans\.length > 0/)
  assert.match(probe, /workouts\.workouts\.length > 0/)
  assert.match(probe, /summary\.totalDurationMs > 0/)
  assert.doesNotMatch(probe, /\/(?:sync|admin|provision)\//)
})

test('MCP source has no static ACCESS_KEY bearer fallback', async () => {
  const sourceFiles = (await readdir(resolve(ROOT, 'src'))).filter((name) => name.endsWith('.ts'))
  const source = (await Promise.all(sourceFiles.map((name) => readFile(resolve(ROOT, 'src', name), 'utf8')))).join('\n')
  assert.doesNotMatch(source, /\bACCESS_KEY\b/)
  assert.match(source, /OAUTH_(?:ISSUER|AUDIENCE|JWKS)/)
})

test('Android P0 gates are explicitly out of this Worker-only repository', async (t) => {
  const artifacts = await findAndroidArtifacts(ROOT)
  assert.deepEqual(
    artifacts,
    [],
    'Android artifacts now exist in watch-cloud-mcp; replace the absence gate with Keystore, backup and release-cleartext tests',
  )
  t.diagnostic('No Android artifacts found: Keystore/backup/release-manifest verification remains an Android-repository gate; see tests/ANDROID_P0_SCOPE.md')
})
