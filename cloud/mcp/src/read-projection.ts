/**
 * Minimal device-authenticated plaintext projection for OAuth MCP reads.
 *
 * The encrypted sync authority remains canonical. This table contains only fields the user
 * explicitly allowed ChatGPT to read: plan names and coarse workout summaries.
 */
type JsonRecord = Record<string, unknown>

export type ReadProjection = {
  plans: Array<{ entityKey: string; name: string }>
  workouts: Array<{
    entityKey: string
    workoutType: 'free' | 'planned'
    startedAt: number
    endedAt: number
    durationMs: number
    distanceMeters: number
    steps: number
  }>
}

type ProjectionRow = {
  projection_type: 'plan' | 'workout'
  projection_key: string
  payload_json: string
  synced_at: string
}

const MAX_ITEMS = 500
const PLAN_KEYS = new Set(['entityKey', 'name'])
const WORKOUT_KEYS = new Set([
  'entityKey', 'workoutType', 'startedAt', 'endedAt', 'durationMs', 'distanceMeters', 'steps',
])

function isRecord(value: unknown): value is JsonRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function exactKeys(value: JsonRecord, expected: ReadonlySet<string>): boolean {
  const keys = Object.keys(value)
  return keys.length === expected.size && keys.every((key) => expected.has(key))
}

function safeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0
}

function parsePlan(value: unknown): ReadProjection['plans'][number] | null {
  if (!isRecord(value) || !exactKeys(value, PLAN_KEYS)) return null
  if (typeof value.entityKey !== 'string' || !/^p:[0-9a-f]{64}$/.test(value.entityKey)) return null
  if (typeof value.name !== 'string' || value.name.trim().length < 1 || value.name.length > 120) return null
  if (/[\u0000-\u001f\u007f]/.test(value.name)) return null
  return { entityKey: value.entityKey, name: value.name }
}

function parseWorkout(value: unknown): ReadProjection['workouts'][number] | null {
  if (!isRecord(value) || !exactKeys(value, WORKOUT_KEYS)) return null
  if (typeof value.entityKey !== 'string' || !/^w:[0-9a-f]{64}$/.test(value.entityKey)) return null
  if (value.workoutType !== 'free' && value.workoutType !== 'planned') return null
  if (!safeInteger(value.startedAt) || !safeInteger(value.endedAt) ||
      !safeInteger(value.durationMs) || !safeInteger(value.steps)) return null
  if (value.endedAt < value.startedAt || value.durationMs > value.endedAt - value.startedAt) return null
  if (typeof value.distanceMeters !== 'number' || !Number.isFinite(value.distanceMeters) ||
      value.distanceMeters < 0 || value.distanceMeters > 1_000_000_000) return null
  if (value.steps > 1_000_000_000) return null
  return {
    entityKey: value.entityKey,
    workoutType: value.workoutType,
    startedAt: value.startedAt,
    endedAt: value.endedAt,
    durationMs: value.durationMs,
    distanceMeters: value.distanceMeters,
    steps: value.steps,
  }
}

export function parseReadProjection(value: unknown): ReadProjection | null {
  if (!isRecord(value) || !exactKeys(value, new Set(['plans', 'workouts']))) return null
  if (!Array.isArray(value.plans) || !Array.isArray(value.workouts) ||
      value.plans.length > MAX_ITEMS || value.workouts.length > MAX_ITEMS) return null
  const plans = value.plans.map(parsePlan)
  const workouts = value.workouts.map(parseWorkout)
  if (plans.some((item) => item === null) || workouts.some((item) => item === null)) return null
  const parsed = { plans: plans as ReadProjection['plans'], workouts: workouts as ReadProjection['workouts'] }
  if (new Set(parsed.plans.map((item) => item.entityKey)).size !== parsed.plans.length ||
      new Set(parsed.workouts.map((item) => item.entityKey)).size !== parsed.workouts.length) return null
  return parsed
}

export async function replaceReadProjection(
  db: D1Database,
  product: string,
  deviceId: string,
  projection: ReadProjection,
  syncedAt: string,
  checkpoint: string,
): Promise<void> {
  if (!/^c[0-9a-z]+$/.test(checkpoint)) throw new Error('invalid_projection_checkpoint')
  const revision = Number.parseInt(checkpoint.slice(1), 36)
  if (!Number.isSafeInteger(revision) || revision < 0) {
    throw new Error('invalid_projection_checkpoint')
  }
  const statements = [
    db.prepare(`
      DELETE FROM watch_read_projection
      WHERE product = ? AND device_id = ? AND projection_type = ?
        AND ? >= COALESCE((
          SELECT revision FROM watch_read_projection_state
          WHERE product = ? AND device_id = ?
        ), -1)
    `).bind(product, deviceId, 'plan', revision, product, deviceId),
    db.prepare(`
      DELETE FROM watch_read_projection
      WHERE product = ? AND device_id = ? AND projection_type = ?
        AND ? >= COALESCE((
          SELECT revision FROM watch_read_projection_state
          WHERE product = ? AND device_id = ?
        ), -1)
    `).bind(product, deviceId, 'workout', revision, product, deviceId),
  ]
  for (const plan of projection.plans) {
    statements.push(db.prepare(`
      INSERT INTO watch_read_projection (
        product, device_id, projection_type, projection_key, payload_json, synced_at
      )
      SELECT ?, ?, 'plan', ?, ?, ?
      WHERE ? >= COALESCE((
        SELECT revision FROM watch_read_projection_state
        WHERE product = ? AND device_id = ?
      ), -1)
    `).bind(
      product, deviceId, plan.entityKey, JSON.stringify({ name: plan.name }), syncedAt,
      revision, product, deviceId,
    ))
  }
  for (const workout of projection.workouts) {
    const { entityKey, ...payload } = workout
    statements.push(db.prepare(`
      INSERT INTO watch_read_projection (
        product, device_id, projection_type, projection_key, payload_json, synced_at
      )
      SELECT ?, ?, 'workout', ?, ?, ?
      WHERE ? >= COALESCE((
        SELECT revision FROM watch_read_projection_state
        WHERE product = ? AND device_id = ?
      ), -1)
    `).bind(
      product, deviceId, entityKey, JSON.stringify(payload), syncedAt,
      revision, product, deviceId,
    ))
  }
  statements.push(db.prepare(`
    INSERT INTO watch_read_projection_state (
      product, device_id, synced_at, checkpoint, revision, plan_count, workout_count
    ) VALUES (?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(product, device_id) DO UPDATE SET
      synced_at = excluded.synced_at,
      checkpoint = excluded.checkpoint,
      revision = excluded.revision,
      plan_count = excluded.plan_count,
      workout_count = excluded.workout_count
    WHERE excluded.revision >= watch_read_projection_state.revision
  `).bind(
    product, deviceId, syncedAt, checkpoint, revision,
    projection.plans.length, projection.workouts.length,
  ))
  await db.batch(statements)
}

function sanitizedRow(row: ProjectionRow): JsonRecord | null {
  try {
    const payload = JSON.parse(row.payload_json)
    const parsed = row.projection_type === 'plan'
      ? parsePlan({ entityKey: row.projection_key, ...payload })
      : parseWorkout({ entityKey: row.projection_key, ...payload })
    if (!parsed) return null
    const { entityKey: _hidden, ...visible } = parsed
    return { ...visible, syncedAt: row.synced_at }
  } catch {
    return null
  }
}

async function latestRows(
  db: D1Database,
  product: string,
  projectionType: 'plan' | 'workout',
): Promise<{ items: JsonRecord[]; truncated: boolean }> {
  const query = await db.prepare(`
    SELECT projection_type, projection_key, payload_json, synced_at FROM (
      SELECT projection_type, projection_key, payload_json, synced_at,
        ROW_NUMBER() OVER (
          PARTITION BY projection_type, projection_key ORDER BY synced_at DESC, device_id DESC
        ) AS rank
      FROM watch_read_projection
      WHERE product = ? AND projection_type = ?
    ) WHERE rank = 1
    ORDER BY synced_at DESC, projection_key
    LIMIT 501
  `).bind(product, projectionType).all<ProjectionRow>()
  const rows = query.results.slice(0, MAX_ITEMS)
  return {
    items: rows.map(sanitizedRow).filter((item): item is JsonRecord => item !== null),
    truncated: query.results.length > MAX_ITEMS,
  }
}

async function projectionReceipt(db: D1Database, product: string): Promise<{
  materialized: boolean
  lastSyncedAt: string | null
  checkpoint: string | null
  revision: number
}> {
  const state = await db.prepare(`
    SELECT COUNT(*) AS receipt_count, MAX(synced_at) AS last_synced_at,
      MAX(revision) AS revision
    FROM watch_read_projection_state
    WHERE product = ?
  `).bind(product).first<{
    receipt_count: number | string
    last_synced_at: string | null
    revision: number | string | null
  }>()
  const revision = Number(state?.revision ?? 0)
  return {
    materialized: Number(state?.receipt_count ?? 0) > 0,
    lastSyncedAt: state?.last_synced_at ?? null,
    checkpoint: Number(state?.receipt_count ?? 0) > 0 ? `c${revision.toString(36)}` : null,
    revision,
  }
}

export async function listProjectedPlans(db: D1Database, product: string): Promise<JsonRecord> {
  const [result, receipt] = await Promise.all([
    latestRows(db, product, 'plan'), projectionReceipt(db, product),
  ])
  return {
    state: receipt.materialized ? 'available' : 'unavailable',
    authority: 'device_uploaded_read_projection',
    fields: ['name', 'syncedAt'],
    plans: result.items,
    lastSyncedAt: receipt.lastSyncedAt,
    checkpoint: receipt.checkpoint,
    truncated: result.truncated,
  }
}

export async function listProjectedWorkouts(db: D1Database, product: string): Promise<JsonRecord> {
  const [result, receipt] = await Promise.all([
    latestRows(db, product, 'workout'), projectionReceipt(db, product),
  ])
  return {
    state: receipt.materialized ? 'available' : 'unavailable',
    authority: 'device_uploaded_read_projection',
    fields: [
      'workoutType', 'startedAt', 'endedAt', 'durationMs', 'distanceMeters', 'steps', 'syncedAt',
    ],
    workouts: result.items,
    lastSyncedAt: receipt.lastSyncedAt,
    checkpoint: receipt.checkpoint,
    truncated: result.truncated,
  }
}

export async function summarizeProjectedWorkouts(
  db: D1Database,
  product: string,
): Promise<JsonRecord> {
  const [result, receipt] = await Promise.all([
    latestRows(db, product, 'workout'), projectionReceipt(db, product),
  ])
  return {
    state: receipt.materialized ? 'available' : 'unavailable',
    authority: 'device_uploaded_read_projection',
    dataClass: 'coarse_activity_health_summary',
    fields: [
      'count', 'plannedWorkoutCount', 'freeWorkoutCount', 'totalDurationMs',
      'totalDistanceMeters', 'totalSteps', 'latestWorkoutEndedAt', 'lastSyncedAt',
    ],
    count: result.items.length,
    plannedWorkoutCount: result.items.filter((item) => item.workoutType === 'planned').length,
    freeWorkoutCount: result.items.filter((item) => item.workoutType === 'free').length,
    totalDurationMs: result.items.reduce((sum, item) => sum + Number(item.durationMs ?? 0), 0),
    totalDistanceMeters: result.items.reduce(
      (sum, item) => sum + Number(item.distanceMeters ?? 0), 0,
    ),
    totalSteps: result.items.reduce((sum, item) => sum + Number(item.steps ?? 0), 0),
    latestWorkoutEndedAt: result.items.reduce(
      (latest, item) => Math.max(latest, Number(item.endedAt ?? 0)), 0,
    ) || null,
    lastSyncedAt: receipt.lastSyncedAt,
    checkpoint: receipt.checkpoint,
    truncated: result.truncated,
  }
}

export async function readProjectionOverview(db: D1Database, product: string): Promise<JsonRecord> {
  const [plans, workouts, receipt] = await Promise.all([
    latestRows(db, product, 'plan'),
    latestRows(db, product, 'workout'),
    projectionReceipt(db, product),
  ])
  return {
    authority: 'device_uploaded_read_projection',
    state: receipt.materialized ? 'available' : 'unavailable',
    planCount: plans.items.length,
    workoutCount: workouts.items.length,
    lastSyncedAt: receipt.lastSyncedAt,
    checkpoint: receipt.checkpoint,
    revision: receipt.revision,
    truncated: plans.truncated || workouts.truncated,
  }
}
