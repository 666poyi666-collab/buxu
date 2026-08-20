import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const baseUrl = 'https://watch-mcp-staging.focuslink-poyi-6465e9.workers.dev'
const wrangler = fileURLToPath(new URL('../node_modules/wrangler/bin/wrangler.js', import.meta.url))
const config = fileURLToPath(new URL('../wrangler.staging.jsonc', import.meta.url))
const deviceId = `watch-v3-probe-${randomUUID()}`
const token = `dw1.${deviceId}.${randomBytes(32).toString('base64url')}`
const tokenHash = createHash('sha256').update(token).digest('hex')
const createdAt = new Date().toISOString()

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

function sql(value) {
  return `'${String(value).replaceAll("'", "''")}'`
}

function d1(command) {
  execFileSync(process.execPath, [
    wrangler, 'd1', 'execute', 'DB', '--remote', '--config', config, '--command', command,
  ], { stdio: 'ignore', windowsHide: true })
}

function envelope(overrides = {}) {
  return {
    protocolVersion: 3,
    requestId: randomUUID(),
    deviceId,
    cursor: null,
    planChanges: [],
    workoutFacts: [],
    sleepRecords: [],
    liveStatus: null,
    commandResults: [],
    ...overrides,
  }
}

async function request(path, { body, authorization = token } = {}) {
  const headers = {}
  if (authorization) headers.authorization = `Bearer ${authorization}`
  if (body !== undefined) headers['content-type'] = 'application/json'
  const response = await fetch(`${baseUrl}${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const raw = await response.text()
  let payload = null
  try { payload = JSON.parse(raw) } catch {}
  return { status: response.status, payload, raw }
}

function cleanup() {
  const stateTime = `(SELECT updated_at FROM watch_v3_device_state WHERE device_id=${sql(deviceId)})`
  d1([
    `DELETE FROM watch_v3_authority_observations WHERE owner_id='poyi-owner' AND revision IN (SELECT revision FROM watch_v3_authority_checkpoints WHERE owner_id='poyi-owner' AND updated_at=${stateTime})`,
    `DELETE FROM watch_v3_authority_checkpoints WHERE owner_id='poyi-owner' AND updated_at=${stateTime}`,
    `DELETE FROM watch_v3_operations WHERE device_id=${sql(deviceId)}`,
    `DELETE FROM watch_v3_device_state WHERE device_id=${sql(deviceId)}`,
    `DELETE FROM encrypted_sync_authority_observations WHERE product='watch' AND revision IN (SELECT revision FROM encrypted_sync_authority_checkpoints WHERE product='watch' AND updated_at=${sql(createdAt)})`,
    `DELETE FROM encrypted_sync_authority_checkpoints WHERE product='watch' AND updated_at=${sql(createdAt)}`,
    `DELETE FROM sync_devices WHERE device_id=${sql(deviceId)}`,
  ].join(';') + ';')
}

try {
  d1(`INSERT INTO sync_devices (device_id,label,token_hash,created_at,revoked_at,last_successful_exchange_at,last_successful_push_at,last_successful_pull_at,last_cursor) VALUES (${sql(deviceId)},'Cloud V3 disposable probe',${sql(tokenHash)},${sql(createdAt)},NULL,NULL,NULL,NULL,NULL)`)

  const body = envelope()
  const created = await request('/sync/v3/exchange', { body })
  assert(created.status === 200, `initial exchange failed: ${created.status}`)
  assert(created.payload?.protocolVersion === 3, 'protocol version mismatch')
  assert(created.payload?.revisionDomainId === 'v3d.watch-staging-owner-v1',
    'revision domain mismatch')
  assert(created.payload?.authority === 'cloud_authoritative', 'authority mismatch')
  assert(created.payload?.nextCursor === 'v3c0', 'initial cursor mismatch')
  assert(created.payload?.planLibrary?.revision === 0, 'empty cloud plan revision mismatch')

  const replay = await request('/sync/v3/exchange', { body })
  assert(replay.status === 200 && replay.payload?.replayed === true, 'request replay mismatch')
  assert(replay.payload?.serverTime === created.payload?.serverTime, 'replay did not preserve first result')

  const now = Date.now()
  const reused = await request('/sync/v3/exchange', { body: {
    ...body,
    liveStatus: {
      statusRevision: 1, observedAt: now, expiresAt: now + 60_000,
      connectionState: 'connected', activeSession: false, sessionState: 'IDLE', planState: 'IDLE', workout: null,
    },
  } })
  assert(reused.status === 409 && reused.payload?.error === 'request_id_reused', 'changed requestId body was not rejected')

  const ahead = await request('/sync/v3/exchange', { body: envelope({ cursor: 'v3c1' }) })
  assert(ahead.status === 409 && ahead.payload?.error === 'cursor_ahead', 'ahead cursor was not rejected')
  assert(ahead.payload?.latestCursor === 'v3c0' && ahead.payload?.resetCursor === 'v3c0', 'cursor reset contract mismatch')

  const privateField = await request('/sync/v3/exchange', { body: { ...envelope(), heartRateSamples: [120] } })
  assert(privateField.status === 400 && privateField.payload?.error === 'invalid_exchange', 'private field was not rejected')

  const mismatched = await request('/sync/v3/exchange', { body: envelope({ deviceId: `watch-v3-other-${randomUUID()}` }) })
  assert(mismatched.status === 403 && mismatched.payload?.error === 'device_mismatch', 'device mismatch was not rejected')

  const channel = await request('/sync/v3/channel')
  assert(channel.status === 426 && channel.payload?.error === 'websocket_required', 'channel upgrade contract mismatch')

  process.stdout.write(JSON.stringify({
    ok: true,
    probes: {
      authenticatedExchange: 'passed', replay: 'passed', requestIdReuse: 'passed',
      cursorAheadReset: 'passed', privateFieldRejection: 'passed', deviceMismatch: 'passed',
      channelUpgradeRequired: 'passed',
    },
    businessFixturesWritten: false,
    credentials: 'not-emitted',
  }) + '\n')
} finally {
  cleanup()
}
