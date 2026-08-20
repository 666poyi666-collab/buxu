/** Private Watch observation exported only through a named service-binding entrypoint. */
export interface AuthorityObservationEnv {
  DB: D1Database
  WATCH_AUTHORITY_CAPABILITY?: string
  WATCH_AUTHORITY_AUDIENCE?: string
  SYNC_KEY?: string
  OAUTH_AUDIENCE?: string
  OAUTH_RS_CLIENT_SECRET?: string
}

type JsonRecord = Record<string, unknown>

export const WATCH_AUTHORITY_PATH = '/_internal/v1/authority-observation'
export const WATCH_AUTHORITY_BINDING = 'WATCH_OBSERVATION'
export const WATCH_AUTHORITY_ENTRYPOINT = 'WatchAuthorityObservation'
export const WATCH_AUTHORITY_MEDIA_TYPE = 'application/vnd.poyi.authority-observation.v1+json'
const PRODUCT = 'watch'
const CAPABILITY_PATTERN = /^wao_[A-Za-z0-9_-]{43}$/
const OBSERVATION_TTL_MILLIS = 5 * 60_000
const FRESH_WINDOW_MILLIS = 15 * 60_000

export type AuthorityTruth = {
  revision: number
  freshness: 'fresh' | 'stale' | 'offline' | 'blocked' | 'unknown'
  lastVerifiedAt: string
  pendingCount: number
  blockerReason: string | null
  pcOff: {
    readAvailable: boolean
    writeAvailable: boolean
    continuedSync: boolean
  }
}

export type AuthorityObservation = {
  schemaVersion: 1
  productId: typeof PRODUCT
  audience: string
  observedAt: string
  expiresAt: string
  truth: AuthorityTruth
}

type DeviceStateRow = {
  cursor: number | string
  last_exchange_at: string | null
  plan_bootstrapped: number | string
}

function exactKeys(value: JsonRecord, expected: readonly string[]): boolean {
  const actual = Object.keys(value).sort()
  const sortedExpected = [...expected].sort()
  return actual.length === sortedExpected.length &&
    actual.every((name, index) => name === sortedExpected[index])
}

function record(value: unknown): value is JsonRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function rfc3339(value: unknown): value is string {
  return typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value) &&
    Number.isFinite(Date.parse(value))
}

function nonNegativeSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0
}

function positiveSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 1
}

function responseHeaders(contentType = 'application/json; charset=UTF-8'): Headers {
  return new Headers({
    'Cache-Control': 'no-store',
    'Content-Type': contentType,
    'X-Content-Type-Options': 'nosniff',
    Vary: 'Accept, Authorization, X-Poyi-Authority-Audience',
  })
}

function json(body: JsonRecord, status = 200, contentType?: string): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: responseHeaders(contentType),
  })
}

function configuredCapability(env: AuthorityObservationEnv): string | null {
  const capability = env.WATCH_AUTHORITY_CAPABILITY ?? ''
  if (!CAPABILITY_PATTERN.test(capability)) return null
  if (capability === env.SYNC_KEY || capability === env.OAUTH_RS_CLIENT_SECRET) return null
  return capability
}

export function validAuthorityAudience(value: unknown): value is string {
  if (typeof value !== 'string' || value.endsWith('/')) return false
  try {
    const audience = new URL(value)
    return audience.protocol === 'https:' && !audience.username && !audience.password &&
      !audience.search && !audience.hash && audience.pathname === `/authority/${PRODUCT}` &&
      audience.href === value
  } catch {
    return false
  }
}

function configuredAudience(env: AuthorityObservationEnv): string | null {
  const raw = env.WATCH_AUTHORITY_AUDIENCE?.trim()
  if (!validAuthorityAudience(raw) || raw === env.OAUTH_AUDIENCE) return null
  return raw
}

export function authorityObservationConfigured(env: AuthorityObservationEnv): boolean {
  return configuredCapability(env) !== null && configuredAudience(env) !== null
}

async function sameSecret(left: string, right: string): Promise<boolean> {
  const encoder = new TextEncoder()
  const [leftHash, rightHash] = await Promise.all([
    crypto.subtle.digest('SHA-256', encoder.encode(left)),
    crypto.subtle.digest('SHA-256', encoder.encode(right)),
  ])
  const leftBytes = new Uint8Array(leftHash)
  const rightBytes = new Uint8Array(rightHash)
  let different = leftBytes.length ^ rightBytes.length
  for (let index = 0; index < leftBytes.length; index += 1) {
    different |= leftBytes[index] ^ (rightBytes[index] ?? 0)
  }
  return different === 0
}

async function authorize(request: Request, env: AuthorityObservationEnv): Promise<
  'ok' | 'not_configured' | 'not_acceptable' | 'invalid_capability' | 'invalid_audience'
> {
  const capability = configuredCapability(env)
  const audience = configuredAudience(env)
  if (!capability || !audience) return 'not_configured'
  if (request.headers.get('accept') !== WATCH_AUTHORITY_MEDIA_TYPE) return 'not_acceptable'
  const match = /^Capability ([A-Za-z0-9_-]+)$/.exec(
    request.headers.get('authorization') ?? '',
  )
  if (!match || !CAPABILITY_PATTERN.test(match[1]) ||
      !await sameSecret(match[1], capability)) return 'invalid_capability'
  if (request.headers.get('x-poyi-authority-audience') !== audience) {
    return 'invalid_audience'
  }
  return 'ok'
}

function latestTimestamp(values: Array<string | null>): string | null {
  const present = values.filter((value): value is string => value !== null)
  if (present.some((value) => !rfc3339(value))) throw new Error('dependency_timestamp_invalid')
  return present.length > 0
    ? present.reduce((latest, value) => value > latest ? value : latest)
    : null
}

export function validateAuthorityObservation(
  value: unknown,
  nowMillis = Date.now(),
  expectedAudience?: string,
): value is AuthorityObservation {
  if (!record(value) || !exactKeys(value,
    ['schemaVersion', 'productId', 'audience', 'observedAt', 'expiresAt', 'truth'])) return false
  if (value.schemaVersion !== 1 || value.productId !== PRODUCT ||
      !validAuthorityAudience(value.audience) ||
      (expectedAudience !== undefined && value.audience !== expectedAudience) ||
      !rfc3339(value.observedAt) || !rfc3339(value.expiresAt) || !record(value.truth)) return false
  const observed = Date.parse(value.observedAt)
  const expires = Date.parse(value.expiresAt)
  if (expires <= observed || expires <= nowMillis) return false

  const truth = value.truth
  if (!exactKeys(truth,
    ['revision', 'freshness', 'lastVerifiedAt', 'pendingCount', 'blockerReason', 'pcOff']) ||
      !positiveSafeInteger(truth.revision) ||
      !['fresh', 'stale', 'offline', 'blocked', 'unknown'].includes(String(truth.freshness)) ||
      !rfc3339(truth.lastVerifiedAt) || !nonNegativeSafeInteger(truth.pendingCount) ||
      !(truth.blockerReason === null || typeof truth.blockerReason === 'string') ||
      !record(truth.pcOff)) return false
  if (Date.parse(truth.lastVerifiedAt) > observed) return false

  const pcOff = truth.pcOff
  if (!exactKeys(pcOff, ['readAvailable', 'writeAvailable', 'continuedSync']) ||
      ![pcOff.readAvailable, pcOff.writeAvailable, pcOff.continuedSync]
        .every((item) => typeof item === 'boolean') ||
      (pcOff.continuedSync && !(pcOff.readAvailable && pcOff.writeAvailable))) return false
  if (truth.freshness === 'fresh' &&
      (truth.pendingCount !== 0 || truth.blockerReason !== null ||
       observed - Date.parse(truth.lastVerifiedAt) > FRESH_WINDOW_MILLIS)) return false
  if (truth.pendingCount > 0 && truth.freshness !== 'blocked') return false
  return true
}

async function checkpointRevision(db: D1Database): Promise<number> {
  const row = await db.prepare(`
    SELECT MAX(revision) AS revision
    FROM watch_v3_authority_checkpoints WHERE owner_id = ?
  `).bind('poyi-owner').first<{ revision: number | string }>()
  const revision = Number(row?.revision)
  if (!positiveSafeInteger(revision)) throw new Error('authority_revision_unavailable')
  return revision
}

async function storedObservation(
  db: D1Database,
  revision: number,
  audience: string,
  nowMillis: number,
): Promise<AuthorityObservation | null> {
  const row = await db.prepare(`
    SELECT observation_json FROM watch_v3_authority_observations
    WHERE owner_id = ? AND revision = ?
  `).bind('poyi-owner', revision).first<{ observation_json: string }>()
  if (!row) return null
  let parsed: unknown
  try { parsed = JSON.parse(row.observation_json) }
  catch { throw new Error('stored_observation_invalid') }
  if (!validateAuthorityObservation(parsed, nowMillis, audience) ||
      parsed.truth.revision !== revision) throw new Error('stored_observation_invalid')
  return parsed
}

async function currentObservation(
  db: D1Database,
  revision: number,
  audience: string,
  now: number,
): Promise<AuthorityObservation> {
  const nowIso = new Date(now).toISOString()
  const [latestRow, devices, pendingCommandRow, materializedRow] = await Promise.all([
    db.prepare(`
      SELECT COALESCE(MAX(change_seq), 0) AS latest
      FROM watch_v3_changes WHERE owner_id = ?
    `).bind('poyi-owner').first<{ latest: number | string }>(),
    db.prepare(`
      SELECT s.cursor, s.last_exchange_at, s.plan_bootstrapped
      FROM watch_v3_device_state s
      JOIN sync_devices d ON d.device_id = s.device_id
      WHERE s.owner_id = ? AND d.revoked_at IS NULL
    `).bind('poyi-owner').all<DeviceStateRow>(),
    db.prepare(`
      SELECT COUNT(*) AS count
      FROM watch_v3_commands
      WHERE owner_id = ? AND status IN ('pending', 'delivered') AND expires_at > ?
    `).bind('poyi-owner', nowIso).first<{ count: number | string }>(),
    db.prepare(`
      SELECT
        (SELECT COUNT(*) FROM watch_v3_plan_libraries WHERE owner_id = ?) AS libraries,
        (SELECT COUNT(*) FROM watch_v3_workouts WHERE owner_id = ? AND tombstoned = 0) AS workouts,
        (SELECT COUNT(*) FROM watch_v3_sleep_records WHERE owner_id = ?) AS sleep_records,
        (SELECT COUNT(*) FROM watch_v3_live_status WHERE owner_id = ?) AS live_status
    `).bind('poyi-owner', 'poyi-owner', 'poyi-owner', 'poyi-owner').first<{
      libraries: number | string
      workouts: number | string
      sleep_records: number | string
      live_status: number | string
    }>(),
  ])
  if (!Number.isSafeInteger(now) || now < 0) throw new Error('authority_clock_invalid')
  const latestSequence = Number(latestRow?.latest)
  if (!nonNegativeSafeInteger(latestSequence)) throw new Error('authority_change_sequence_invalid')
  const lastVerifiedAt = latestTimestamp(
    devices.results.map((device) => device.last_exchange_at),
  )
  if (!lastVerifiedAt) throw new Error('authority_last_verified_unavailable')
  const lastVerifiedMillis = Date.parse(lastVerifiedAt)
  if (lastVerifiedMillis > now) throw new Error('authority_last_verified_invalid')

  let unreadChanges = 0
  let invalidCheckpoint = false
  for (const device of devices.results) {
    const cursor = Number(device.cursor)
    if (!nonNegativeSafeInteger(cursor) || cursor > latestSequence) {
      invalidCheckpoint = true
      unreadChanges += latestSequence
    } else {
      unreadChanges += latestSequence - cursor
    }
  }

  const pendingCommands = Number(pendingCommandRow?.count ?? 0)
  const materializedCounts = [
    materializedRow?.libraries, materializedRow?.workouts,
    materializedRow?.sleep_records, materializedRow?.live_status,
  ].map((value) => Number(value ?? 0))
  const dataMaterialized = materializedCounts.some((count) => count > 0)
  const planBootstrapped = devices.results.some((device) => Number(device.plan_bootstrapped) === 1)
  const pendingCount = unreadChanges + pendingCommands
  if (!nonNegativeSafeInteger(pendingCommands) ||
      materializedCounts.some((count) => !nonNegativeSafeInteger(count)) ||
      !nonNegativeSafeInteger(pendingCount)) throw new Error('authority_pending_invalid')

  let blockerReason: string | null = null
  if (invalidCheckpoint) blockerReason = 'invalid_device_checkpoint'
  else if (devices.results.length === 0) blockerReason = 'no_v3_device_receipt'
  else if (!planBootstrapped || !dataMaterialized) blockerReason = 'v3_bootstrap_incomplete'
  else if (unreadChanges > 0) blockerReason = 'device_catchup_pending'
  else if (pendingCommands > 0) blockerReason = 'pending_device_command'

  const freshness: AuthorityTruth['freshness'] = pendingCount > 0 || blockerReason !== null
    ? 'blocked'
    : now - lastVerifiedMillis <= FRESH_WINDOW_MILLIS ? 'fresh' : 'stale'
  const observedAt = new Date(now).toISOString()
  const observation: AuthorityObservation = {
    schemaVersion: 1,
    productId: PRODUCT,
    audience,
    observedAt,
    expiresAt: new Date(now + OBSERVATION_TTL_MILLIS).toISOString(),
    truth: {
      revision,
      freshness,
      lastVerifiedAt,
      pendingCount,
      blockerReason,
      // PC-off capability remains locked until production data, three PC-off rounds,
      // and local-service retirement have all been accepted.
      pcOff: { readAvailable: false, writeAvailable: false, continuedSync: false },
    },
  }
  if (!validateAuthorityObservation(observation, now, audience)) {
    throw new Error('authority_observation_invalid')
  }
  return observation
}

export async function buildAuthorityObservation(
  db: D1Database,
  audience: string,
  now = Date.now(),
): Promise<AuthorityObservation> {
  if (!validAuthorityAudience(audience)) throw new Error('authority_audience_invalid')
  // Checkpoints move only when D1 authority state changes. A stored revision is immutable, so the
  // signing authority computes the same observationHash for every successful read of that revision.
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const revision = await checkpointRevision(db)
    const existing = await storedObservation(db, revision, audience, now)
    if (existing) return existing
    const observation = await currentObservation(db, revision, audience, now)
    if (await checkpointRevision(db) !== revision) continue
    await db.batch([
      db.prepare(`
        INSERT OR IGNORE INTO watch_v3_authority_observations (
          owner_id, revision, observation_json, observed_at, expires_at
        ) VALUES (?, ?, ?, ?, ?)
      `).bind('poyi-owner', revision, JSON.stringify(observation), observation.observedAt,
        observation.expiresAt),
      db.prepare(`
        DELETE FROM watch_v3_authority_observations
        WHERE owner_id = ? AND revision < ?
      `).bind('poyi-owner', Math.max(1, revision - 1)),
    ])
    const stored = await storedObservation(db, revision, audience, now)
    if (!stored) throw new Error('stored_observation_missing')
    return stored
  }
  throw new Error('authority_checkpoint_changed')
}

export async function authorityObservation(
  request: Request,
  env: AuthorityObservationEnv,
): Promise<Response> {
  const url = new URL(request.url)
  if (url.pathname !== WATCH_AUTHORITY_PATH || url.search || url.hash) {
    return json({ error: 'not_found' }, 404)
  }
  if (request.method !== 'GET') return json({ error: 'method_not_allowed' }, 405)
  const authorization = await authorize(request, env)
  if (authorization === 'not_configured') {
    return json({ error: 'authority_observation_not_configured' }, 503)
  }
  if (authorization === 'not_acceptable') {
    return json({ error: 'authority_observation_not_acceptable' }, 406)
  }
  if (authorization === 'invalid_capability') {
    const response = json({ error: 'unauthorized' }, 401)
    response.headers.set('WWW-Authenticate', 'Capability')
    return response
  }
  if (authorization === 'invalid_audience') {
    return json({ error: 'authority_audience_mismatch' }, 403)
  }
  try {
    const audience = configuredAudience(env)
    if (!audience) throw new Error('authority_audience_unavailable')
    return json(
      await buildAuthorityObservation(env.DB, audience),
      200,
      WATCH_AUTHORITY_MEDIA_TYPE,
    )
  } catch {
    return json({ error: 'authority_observation_unavailable' }, 503)
  }
}
