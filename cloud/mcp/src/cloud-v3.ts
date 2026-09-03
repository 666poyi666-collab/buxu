export interface CloudV3Env {
  DB: D1Database
  COMMAND_CHANNEL?: DurableObjectNamespace
  WATCH_PLAN_REVISION_DOMAIN_ID?: string
}

type JsonRecord = Record<string, unknown>
type DeviceRow = { device_id: string; token_hash: string; revoked_at: string | null }
type PlanLibrary = {
  schemaVersion: 1
  selectedPlanId: string | null
  groups: JsonRecord[]
  plans: JsonRecord[]
}

const OWNER_ID = 'poyi-owner'
const MAX_EXCHANGE_BYTES = 1024 * 1024
const MAX_ITEMS = 25
const MAX_CHANGES = 100
const COMMAND_TTL_MS = 30_000
const CONTROL_WAIT_MS = 10_000
const DEVICE_TOKEN_PREFIX = 'dw1'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const ENTITY_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/
const REQUEST_KEYS = new Set([
  'protocolVersion', 'requestId', 'deviceId', 'cursor', 'planChanges', 'workoutFacts',
  'sleepRecords', 'liveStatus', 'commandResults',
])
const PLAN_CHANGE_KEYS = new Set(['operationId', 'expectedRevision', 'library'])
const LIBRARY_KEYS = new Set(['schemaVersion', 'selectedPlanId', 'groups', 'plans'])
const GROUP_KEYS = new Set(['id', 'name', 'sortOrder'])
const PLAN_KEYS = new Set(['id', 'name', 'groupId', 'requirement', 'sortOrder', 'stages'])
const STAGE_KEYS = new Set(['kind', 'unit', 'target'])
const FACT_KEYS = new Set(['operationId', 'workout'])
const WORKOUT_KEYS = new Set([
  'schemaVersion', 'id', 'startedAt', 'endedAt', 'durationMs', 'pausedDurationMs',
  'elapsedDurationMs', 'distanceMeters', 'steps', 'averageHeartRate', 'plan', 'planName',
  'planGroup', 'planRequirement', 'planCompletedActiveMs', 'planCompletedWallTime',
  'freeRecordingActiveMs', 'planDistanceMeters', 'freeRecordingDistanceMeters',
  'maxSmoothedSpeedMps', 'routePointCount', 'stageResults', 'averagePaceSecondsPerKm',
  'averageCadenceSpm', 'elevationGainMeters', 'splits', 'bestPaceSecondsPerKm',
  'heartRateRange', 'dataSourceSummary',
])
const REQUIRED_WORKOUT_KEYS = new Set([
  'schemaVersion', 'id', 'startedAt', 'endedAt', 'durationMs', 'distanceMeters', 'steps',
  'averageHeartRate', 'plan', 'planName', 'planGroup', 'planRequirement', 'stageResults',
])
const STAGE_RESULT_KEYS = new Set([
  'index', 'name', 'unit', 'target', 'completedAtMs', 'totalDistanceMeters',
])
const SPLIT_KEYS = new Set(['index', 'distanceMeters', 'durationMs', 'paceSecondsPerKm'])
const RANGE_KEYS = new Set(['min', 'max'])
const SOURCE_SUMMARY_KEYS = new Set([
  'distanceSource', 'speedSource', 'heartRateSource', 'locationAccuracyClass',
])
const SLEEP_ITEM_KEYS = new Set(['operationId', 'recordId', 'sourceRevision', 'record'])
const SLEEP_KEYS = new Set([
  'timestamp', 'totalDurationMinutes', 'sleepScore', 'spo2AveragePercent', 'osaResult',
  'heartRateBenchmarkBpm', 'breathRateBenchmarkPerMinute', 'heartRateRangeBpm',
  'breathRateRangePerMinute', 'sessions',
])
const SLEEP_RANGE_KEYS = new Set(['minimum', 'maximum'])
const SESSION_KEYS = new Set([
  'startTime', 'endTime', 'sleepDurationMinutes', 'deepDurationMinutes',
  'lightDurationMinutes', 'remDurationMinutes', 'awakeDurationMinutes', 'stages',
])
const SLEEP_STAGE_KEYS = new Set(['type', 'label', 'startTime', 'endTime'])
const LIVE_KEYS = new Set([
  'statusRevision', 'observedAt', 'expiresAt', 'connectionState', 'activeSession',
  'sessionState', 'planState', 'workout',
])
const LIVE_WORKOUT_KEYS = new Set([
  'activeDurationMs', 'distanceMeters', 'paceSecondsPerKm', 'speedMps', 'steps',
  'heartRate', 'averageHeartRate', 'maximumHeartRate', 'cadenceSpm',
  'elevationGainMeters', 'stageName', 'stageNumber', 'stageCount',
])
const COMMAND_RESULT_KEYS = new Set([
  'commandId', 'outcome', 'actualState', 'controlRevision', 'completedAt', 'error',
])
const FORBIDDEN_KEYS = new Set([
  'route', 'routes', 'latitude', 'longitude', 'coordinates', 'heartRateSamples',
  'heartSamples', 'token', 'accessToken', 'refreshToken', 'pairingCode',
])

function isRecord(value: unknown): value is JsonRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function exact(value: JsonRecord, keys: ReadonlySet<string>): boolean {
  const actual = Object.keys(value)
  return actual.length === keys.size && actual.every((key) => keys.has(key))
}

function only(value: JsonRecord, keys: ReadonlySet<string>): boolean {
  return Object.keys(value).every((key) => keys.has(key))
}

function hasRequired(value: JsonRecord, keys: ReadonlySet<string>): boolean {
  return [...keys].every((key) => Object.hasOwn(value, key))
}

function safeInteger(value: unknown, minimum = 0): value is number {
  return Number.isSafeInteger(value) && Number(value) >= minimum
}

function signedInteger(value: unknown): value is number {
  return Number.isSafeInteger(value)
}

function finite(value: unknown, minimum = 0): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= minimum
}

function boundedString(value: unknown, maximum = 200, allowEmpty = true): value is string {
  return typeof value === 'string' && value.length <= maximum && (allowEmpty || value.trim().length > 0)
}

function validId(value: unknown): value is string {
  return typeof value === 'string' && ENTITY_ID.test(value)
}

function validUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value)
}

function forbiddenField(value: unknown): string | null {
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = forbiddenField(item)
      if (found) return found
    }
    return null
  }
  if (!isRecord(value)) return null
  for (const [key, child] of Object.entries(value)) {
    if (FORBIDDEN_KEYS.has(key)) return key
    const found = forbiddenField(child)
    if (found) return found
  }
  return null
}

function stableJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`
  if (!isRecord(value)) return JSON.stringify(value)
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

function bearer(request: Request): string | null {
  return /^Bearer\s+([^\s]+)$/i.exec(request.headers.get('authorization') ?? '')?.[1] ?? null
}

function tokenDeviceId(token: string | null): string | null {
  if (!token) return null
  return new RegExp(`^${DEVICE_TOKEN_PREFIX}\\.([A-Za-z0-9][A-Za-z0-9_-]{2,127})\\.[A-Za-z0-9_-]{32,}$`).exec(token)?.[1] ?? null
}

async function authenticateDevice(request: Request, env: CloudV3Env): Promise<DeviceRow | null> {
  const token = bearer(request)
  const deviceId = tokenDeviceId(token)
  if (!token || !deviceId) return null
  return env.DB.prepare(
    'SELECT device_id, token_hash, revoked_at FROM sync_devices WHERE device_id = ? AND token_hash = ? AND revoked_at IS NULL',
  ).bind(deviceId, await sha256(token)).first<DeviceRow>()
}

function encodeCursor(value: number): string {
  return `v3c${Math.max(0, Math.trunc(value)).toString(36)}`
}

function decodeCursor(value: unknown): number | null {
  if (value === null) return 0
  if (typeof value !== 'string' || !/^v3c[0-9a-z]+$/i.test(value)) return null
  const result = Number.parseInt(value.slice(3), 36)
  return Number.isSafeInteger(result) && result >= 0 ? result : null
}

function json(payload: unknown, status = 200): Response {
  return Response.json(payload, { status, headers: { 'Cache-Control': 'no-store' } })
}

function errorJson(error: string, status: number, extra: JsonRecord = {}): Response {
  return json({ error, ...extra }, status)
}

export function cloudV3Configured(env: CloudV3Env): boolean {
  return /^v3d\.[A-Za-z0-9_-]{8,64}$/.test(env.WATCH_PLAN_REVISION_DOMAIN_ID ?? '')
}

async function readJson(request: Request): Promise<unknown> {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get('content-type') ?? '')) {
    throw new Error('unsupported_media_type')
  }
  const length = Number(request.headers.get('content-length') ?? 0)
  if (Number.isFinite(length) && length > MAX_EXCHANGE_BYTES) throw new Error('payload_too_large')
  const body = await request.text()
  if (new TextEncoder().encode(body).byteLength > MAX_EXCHANGE_BYTES) throw new Error('payload_too_large')
  try { return JSON.parse(body) } catch { throw new Error('invalid_json') }
}

function validGroup(value: unknown): value is JsonRecord {
  return isRecord(value) && exact(value, GROUP_KEYS) && validId(value.id)
    && boundedString(value.name, 200, false) && safeInteger(value.sortOrder)
}

function validStage(value: unknown): value is JsonRecord {
  return isRecord(value) && exact(value, STAGE_KEYS)
    && ['RUN', 'WALK', 'REST'].includes(String(value.kind))
    && ['DISTANCE', 'TIME'].includes(String(value.unit))
    && safeInteger(value.target, 1)
}

function validPlan(value: unknown): value is JsonRecord {
  return isRecord(value) && exact(value, PLAN_KEYS) && validId(value.id)
    && boundedString(value.name, 200, false)
    && (value.groupId === null || validId(value.groupId))
    && boundedString(value.requirement, 2000)
    && safeInteger(value.sortOrder)
    && Array.isArray(value.stages) && value.stages.length >= 1 && value.stages.length <= 100
    && value.stages.every(validStage)
}

function validLibrary(value: unknown): value is PlanLibrary {
  if (!isRecord(value) || !exact(value, LIBRARY_KEYS) || value.schemaVersion !== 1) return false
  if (value.selectedPlanId !== null && !validId(value.selectedPlanId)) return false
  if (!Array.isArray(value.groups) || value.groups.length > 200 || !value.groups.every(validGroup)) return false
  if (!Array.isArray(value.plans) || value.plans.length > 500 || !value.plans.every(validPlan)) return false
  const groupIds = new Set(value.groups.map((group) => String((group as JsonRecord).id)))
  const planIds = new Set(value.plans.map((plan) => String((plan as JsonRecord).id)))
  if (groupIds.size !== value.groups.length || planIds.size !== value.plans.length) return false
  if (value.plans.some((plan) => (plan as JsonRecord).groupId !== null && !groupIds.has(String((plan as JsonRecord).groupId)))) return false
  return value.selectedPlanId === null || planIds.has(value.selectedPlanId)
}

/**
 * Projects a stage onto exactly the wire shape so pre-existing extra fields in D1 can never
 * poison a later exchange. Semantic values (kind/unit/target) are preserved, never coerced.
 */
function sanitizeStage(stage: JsonRecord): JsonRecord {
  return { kind: stage.kind, unit: stage.unit, target: stage.target }
}

/** Strips non-wire fields from a group without changing its semantic values. */
function sanitizeGroup(group: JsonRecord): JsonRecord {
  return { id: group.id, name: group.name, sortOrder: group.sortOrder }
}

/** Strips non-wire fields from a plan and its stages without changing semantic values. */
function sanitizePlan(plan: JsonRecord): JsonRecord {
  return {
    id: plan.id,
    name: plan.name,
    groupId: plan.groupId ?? null,
    requirement: plan.requirement,
    sortOrder: plan.sortOrder,
    stages: Array.isArray(plan.stages)
      ? plan.stages.map((stage) => sanitizeStage(stage as JsonRecord))
      : plan.stages,
  }
}

function validStageResult(value: unknown): boolean {
  return isRecord(value) && exact(value, STAGE_RESULT_KEYS) && safeInteger(value.index, 1)
    && boundedString(value.name, 200) && ['DISTANCE', 'TIME'].includes(String(value.unit))
    && safeInteger(value.target) && safeInteger(value.completedAtMs)
    && finite(value.totalDistanceMeters)
}

function validSplit(value: unknown): boolean {
  return isRecord(value) && exact(value, SPLIT_KEYS) && safeInteger(value.index, 1)
    && finite(value.distanceMeters) && safeInteger(value.durationMs) && finite(value.paceSecondsPerKm)
}

function validWorkout(value: unknown): value is JsonRecord {
  if (!isRecord(value) || !only(value, WORKOUT_KEYS) || !hasRequired(value, REQUIRED_WORKOUT_KEYS)) return false
  if (!validId(value.id) || !safeInteger(value.schemaVersion, 1) || !safeInteger(value.startedAt)
    || !safeInteger(value.endedAt) || !safeInteger(value.durationMs) || !finite(value.distanceMeters)
    || !safeInteger(value.steps) || !finite(value.averageHeartRate)
    || !boundedString(value.plan, 100_000) || !boundedString(value.planName, 200)
    || !boundedString(value.planGroup, 200) || !boundedString(value.planRequirement, 2000)
    || !Array.isArray(value.stageResults) || !value.stageResults.every(validStageResult)) return false
  const integerOptionals = [
    'pausedDurationMs', 'elapsedDurationMs', 'planCompletedActiveMs', 'planCompletedWallTime',
    'freeRecordingActiveMs', 'routePointCount', 'averagePaceSecondsPerKm', 'bestPaceSecondsPerKm',
  ]
  if (integerOptionals.some((key) => value[key] !== undefined && !safeInteger(value[key]))) return false
  const numberOptionals = [
    'planDistanceMeters', 'freeRecordingDistanceMeters', 'maxSmoothedSpeedMps',
    'averageCadenceSpm', 'elevationGainMeters',
  ]
  if (numberOptionals.some((key) => value[key] !== undefined && !finite(value[key]))) return false
  if (value.splits !== undefined && (!Array.isArray(value.splits) || !value.splits.every(validSplit))) return false
  if (value.heartRateRange !== undefined && (!isRecord(value.heartRateRange)
    || !exact(value.heartRateRange, RANGE_KEYS) || !finite(value.heartRateRange.min)
    || !finite(value.heartRateRange.max))) return false
  return value.dataSourceSummary === undefined || (isRecord(value.dataSourceSummary)
    && exact(value.dataSourceSummary, SOURCE_SUMMARY_KEYS)
    && Object.values(value.dataSourceSummary).every((item) => boundedString(item, 100)))
}

function validSleepRange(value: unknown): boolean {
  return isRecord(value) && exact(value, SLEEP_RANGE_KEYS)
    && finite(value.minimum) && finite(value.maximum)
}

function validSleepStage(value: unknown): boolean {
  return isRecord(value) && exact(value, SLEEP_STAGE_KEYS) && safeInteger(value.type)
    && boundedString(value.label, 100) && safeInteger(value.startTime) && safeInteger(value.endTime)
}

function validSleepSession(value: unknown): boolean {
  return isRecord(value) && exact(value, SESSION_KEYS) && safeInteger(value.startTime)
    && safeInteger(value.endTime) && safeInteger(value.sleepDurationMinutes)
    && safeInteger(value.deepDurationMinutes) && safeInteger(value.lightDurationMinutes)
    && safeInteger(value.remDurationMinutes) && safeInteger(value.awakeDurationMinutes)
    && Array.isArray(value.stages) && value.stages.length <= 500 && value.stages.every(validSleepStage)
}

function validSleepRecord(value: unknown): value is JsonRecord {
  return isRecord(value) && exact(value, SLEEP_KEYS) && safeInteger(value.timestamp)
    && safeInteger(value.totalDurationMinutes) && safeInteger(value.sleepScore)
    && safeInteger(value.spo2AveragePercent) && signedInteger(value.osaResult)
    && safeInteger(value.heartRateBenchmarkBpm) && finite(value.breathRateBenchmarkPerMinute)
    && validSleepRange(value.heartRateRangeBpm) && validSleepRange(value.breathRateRangePerMinute)
    && Array.isArray(value.sessions) && value.sessions.length <= 50 && value.sessions.every(validSleepSession)
}

function validLiveWorkout(value: unknown): boolean {
  if (value === null) return true
  if (!isRecord(value) || !exact(value, LIVE_WORKOUT_KEYS)) return false
  const integers = ['activeDurationMs', 'steps', 'heartRate', 'averageHeartRate', 'maximumHeartRate', 'stageNumber', 'stageCount']
  const numbers = ['distanceMeters', 'paceSecondsPerKm', 'speedMps', 'cadenceSpm', 'elevationGainMeters']
  return integers.every((key) => safeInteger(value[key])) && numbers.every((key) => finite(value[key]))
    && boundedString(value.stageName, 200)
}

function validLiveStatus(value: unknown): value is JsonRecord {
  return isRecord(value) && exact(value, LIVE_KEYS) && safeInteger(value.statusRevision)
    && safeInteger(value.observedAt) && safeInteger(value.expiresAt)
    && value.expiresAt >= value.observedAt && value.expiresAt - value.observedAt <= 120_000
    && boundedString(value.connectionState, 100) && typeof value.activeSession === 'boolean'
    && boundedString(value.sessionState, 100) && boundedString(value.planState, 100)
    && validLiveWorkout(value.workout)
}

type ParsedExchange = {
  requestId: string
  deviceId: string
  cursor: number
  planChanges: JsonRecord[]
  workoutFacts: JsonRecord[]
  sleepRecords: JsonRecord[]
  liveStatus: JsonRecord | null
  commandResults: JsonRecord[]
}

function parseExchange(value: unknown): ParsedExchange | null {
  if (!isRecord(value) || !exact(value, REQUEST_KEYS) || value.protocolVersion !== 3
    || !validUuid(value.requestId) || !validId(value.deviceId)) return null
  const cursor = decodeCursor(value.cursor)
  if (cursor === null) return null
  const arrays = ['planChanges', 'workoutFacts', 'sleepRecords', 'commandResults'] as const
  if (arrays.some((key) => !Array.isArray(value[key]) || (value[key] as unknown[]).length > MAX_ITEMS)) return null
  const planChanges = value.planChanges as unknown[]
  if (!planChanges.every((item) => isRecord(item) && exact(item, PLAN_CHANGE_KEYS)
    && validUuid(item.operationId) && safeInteger(item.expectedRevision) && validLibrary(item.library))) return null
  const workoutFacts = value.workoutFacts as unknown[]
  if (!workoutFacts.every((item) => isRecord(item) && exact(item, FACT_KEYS)
    && validUuid(item.operationId) && validWorkout(item.workout))) return null
  const sleepRecords = value.sleepRecords as unknown[]
  if (!sleepRecords.every((item) => isRecord(item) && exact(item, SLEEP_ITEM_KEYS)
    && validUuid(item.operationId) && validId(item.recordId)
    && boundedString(item.sourceRevision, 128, false) && validSleepRecord(item.record))) return null
  const commandResults = value.commandResults as unknown[]
  if (!commandResults.every((item) => isRecord(item) && exact(item, COMMAND_RESULT_KEYS)
    && validUuid(item.commandId) && ['succeeded', 'failed'].includes(String(item.outcome))
    && boundedString(item.actualState, 100) && safeInteger(item.controlRevision)
    && safeInteger(item.completedAt) && (item.error === null || boundedString(item.error, 500)))) return null
  if (value.liveStatus !== null && !validLiveStatus(value.liveStatus)) return null
  const operationIds = [value.requestId, ...planChanges, ...workoutFacts, ...sleepRecords]
    .map((item) => typeof item === 'string' ? item : String((item as JsonRecord).operationId))
  if (new Set(operationIds).size !== operationIds.length || forbiddenField(value)) return null
  return {
    requestId: value.requestId,
    deviceId: value.deviceId,
    cursor,
    planChanges: planChanges as JsonRecord[],
    workoutFacts: workoutFacts as JsonRecord[],
    sleepRecords: sleepRecords as JsonRecord[],
    liveStatus: value.liveStatus as JsonRecord | null,
    commandResults: commandResults as JsonRecord[],
  }
}

async function deviceState(env: CloudV3Env, deviceId: string, timestamp: string): Promise<void> {
  await env.DB.prepare(
    'INSERT INTO watch_v3_device_state (device_id, owner_id, cursor, plan_bootstrapped, created_at, updated_at) '
      + 'VALUES (?, ?, 0, 0, ?, ?) ON CONFLICT(device_id) DO UPDATE SET updated_at = excluded.updated_at',
  ).bind(deviceId, OWNER_ID, timestamp, timestamp).run()
}

async function operationReplay(
  env: CloudV3Env, operationId: string, requestHash: string,
): Promise<JsonRecord | 'reused' | null> {
  const row = await env.DB.prepare(
    'SELECT request_hash, result_json FROM watch_v3_operations WHERE owner_id = ? AND operation_id = ?',
  ).bind(OWNER_ID, operationId).first<{ request_hash: string; result_json: string }>()
  if (!row) return null
  if (row.request_hash !== requestHash) return 'reused'
  try { return JSON.parse(row.result_json) as JsonRecord } catch { return 'reused' }
}

async function saveOperation(
  env: CloudV3Env, deviceId: string | null, operationId: string, operationType: string,
  requestHash: string, result: JsonRecord, timestamp: string,
): Promise<void> {
  await env.DB.prepare(
    'INSERT INTO watch_v3_operations '
      + '(owner_id, operation_id, device_id, operation_type, request_hash, result_json, created_at) '
      + 'VALUES (?, ?, ?, ?, ?, ?, ?)',
  ).bind(OWNER_ID, operationId, deviceId, operationType, requestHash, JSON.stringify(result), timestamp).run()
}

async function loadPlanLibrary(db: D1Database): Promise<JsonRecord> {
  const library = await db.prepare(
    'SELECT revision, selected_plan_id, updated_at FROM watch_v3_plan_libraries WHERE owner_id = ?',
  ).bind(OWNER_ID).first<{ revision: number | string; selected_plan_id: string | null; updated_at: string }>()
  if (!library) return { revision: 0, selectedPlanId: null, groups: [], plans: [], updatedAt: null }
  const [groups, plans] = await Promise.all([
    db.prepare('SELECT payload_json FROM watch_v3_plan_groups WHERE owner_id = ? ORDER BY sort_order, group_id')
      .bind(OWNER_ID).all<{ payload_json: string }>(),
    db.prepare('SELECT payload_json FROM watch_v3_plans WHERE owner_id = ? ORDER BY sort_order, plan_id')
      .bind(OWNER_ID).all<{ payload_json: string }>(),
  ])
  const sanitizedGroups = groups.results.map((row) => sanitizeGroup(JSON.parse(row.payload_json)))
  const sanitizedPlans = plans.results.map((row) => sanitizePlan(JSON.parse(row.payload_json)))
  const planIds = new Set(sanitizedPlans.map((plan) => String(plan.id)))
  const selectedPlanId = typeof library.selected_plan_id === 'string'
    && planIds.has(library.selected_plan_id) ? library.selected_plan_id : null
  return {
    revision: Number(library.revision),
    selectedPlanId,
    groups: sanitizedGroups,
    plans: sanitizedPlans,
    updatedAt: library.updated_at,
  }
}

async function applyPlanChange(
  env: CloudV3Env, deviceId: string | null, change: JsonRecord, timestamp: string,
): Promise<JsonRecord> {
  const requestHash = await sha256(stableJson(change))
  const replay = await operationReplay(env, String(change.operationId), requestHash)
  if (replay === 'reused') return { operationId: change.operationId, outcome: 'conflict', error: 'operation_id_reused' }
  if (replay) return { ...replay, replayed: true }
  const row = await env.DB.prepare(
    'SELECT revision FROM watch_v3_plan_libraries WHERE owner_id = ?',
  ).bind(OWNER_ID).first<{ revision: number | string }>()
  const currentRevision = Number(row?.revision ?? 0)
  if (Number(change.expectedRevision) !== currentRevision) {
    const result = {
      operationId: change.operationId, outcome: 'conflict', error: 'revision_conflict',
      expectedRevision: change.expectedRevision, currentRevision,
    }
    await saveOperation(env, deviceId, String(change.operationId), 'plan_library', requestHash, result, timestamp)
    return result
  }
  const nextRevision = currentRevision + 1
  const library = change.library as PlanLibrary
  const operationId = String(change.operationId)
  const statements: D1PreparedStatement[] = [
    env.DB.prepare(
      'INSERT INTO watch_v3_plan_libraries '
        + '(owner_id, revision, selected_plan_id, updated_at, updated_by_device_id, last_operation_id) '
        + 'VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(owner_id) DO UPDATE SET revision = excluded.revision, '
        + 'selected_plan_id = excluded.selected_plan_id, updated_at = excluded.updated_at, '
        + 'updated_by_device_id = excluded.updated_by_device_id, last_operation_id = excluded.last_operation_id '
        + 'WHERE watch_v3_plan_libraries.revision = ?',
    ).bind(OWNER_ID, nextRevision, library.selectedPlanId, timestamp, deviceId, operationId, currentRevision),
    env.DB.prepare(
      'DELETE FROM watch_v3_plan_groups WHERE owner_id = ? '
        + 'AND EXISTS (SELECT 1 FROM watch_v3_plan_libraries WHERE owner_id = ? AND last_operation_id = ?)',
    ).bind(OWNER_ID, OWNER_ID, operationId),
    env.DB.prepare(
      'DELETE FROM watch_v3_plans WHERE owner_id = ? '
        + 'AND EXISTS (SELECT 1 FROM watch_v3_plan_libraries WHERE owner_id = ? AND last_operation_id = ?)',
    ).bind(OWNER_ID, OWNER_ID, operationId),
  ]
  for (const group of library.groups) {
    statements.push(env.DB.prepare(
      'INSERT INTO watch_v3_plan_groups (owner_id, group_id, name, sort_order, payload_json, updated_at) '
        + 'SELECT ?, ?, ?, ?, ?, ? WHERE EXISTS '
        + '(SELECT 1 FROM watch_v3_plan_libraries WHERE owner_id = ? AND last_operation_id = ?)',
    ).bind(
      OWNER_ID, group.id, group.name, group.sortOrder, JSON.stringify(group), timestamp,
      OWNER_ID, operationId,
    ))
  }
  for (const plan of library.plans) {
    statements.push(env.DB.prepare(
      'INSERT INTO watch_v3_plans (owner_id, plan_id, group_id, name, sort_order, payload_json, updated_at) '
        + 'SELECT ?, ?, ?, ?, ?, ?, ? WHERE EXISTS '
        + '(SELECT 1 FROM watch_v3_plan_libraries WHERE owner_id = ? AND last_operation_id = ?)',
    ).bind(
      OWNER_ID, plan.id, plan.groupId, plan.name, plan.sortOrder, JSON.stringify(plan), timestamp,
      OWNER_ID, operationId,
    ))
  }
  statements.push(env.DB.prepare(
    'INSERT INTO watch_v3_changes '
      + '(owner_id, change_type, entity_id, entity_revision, operation, payload_json, changed_at, origin_device_id, operation_id) '
      + "SELECT ?, 'plan_library', 'library', ?, 'upsert', ?, ?, ?, ? WHERE EXISTS "
      + '(SELECT 1 FROM watch_v3_plan_libraries WHERE owner_id = ? AND last_operation_id = ?)',
  ).bind(
    OWNER_ID, nextRevision, JSON.stringify(library), timestamp, deviceId, operationId,
    OWNER_ID, operationId,
  ))
  await env.DB.batch(statements)
  const committed = await env.DB.prepare(
    'SELECT revision, last_operation_id FROM watch_v3_plan_libraries WHERE owner_id = ?',
  ).bind(OWNER_ID).first<{ revision: number | string; last_operation_id: string }>()
  const result: JsonRecord = committed?.last_operation_id === operationId
    ? { operationId, outcome: 'acknowledged', revision: nextRevision }
    : {
        operationId, outcome: 'conflict', error: 'revision_conflict',
        expectedRevision: change.expectedRevision, currentRevision: Number(committed?.revision ?? 0),
      }
  await saveOperation(env, deviceId, operationId, 'plan_library', requestHash, result, timestamp)
  if (deviceId && result.outcome === 'acknowledged') {
    await env.DB.prepare('UPDATE watch_v3_device_state SET plan_bootstrapped = 1 WHERE device_id = ?')
      .bind(deviceId).run()
  }
  return result
}

async function applyWorkoutFact(
  env: CloudV3Env, deviceId: string, fact: JsonRecord, timestamp: string,
): Promise<JsonRecord> {
  const requestHash = await sha256(stableJson(fact))
  const operationId = String(fact.operationId)
  const replay = await operationReplay(env, operationId, requestHash)
  if (replay === 'reused') return { operationId, outcome: 'conflict', error: 'operation_id_reused' }
  if (replay) return { ...replay, replayed: true }
  const workout = fact.workout as JsonRecord
  const payloadHash = await sha256(stableJson(workout))
  const tombstone = await env.DB.prepare(
    'SELECT command_id FROM watch_v3_workout_tombstones WHERE owner_id = ? AND workout_id = ?',
  ).bind(OWNER_ID, workout.id).first<{ command_id: string }>()
  let result: JsonRecord
  if (tombstone) {
    result = { operationId, outcome: 'conflict', workoutId: workout.id, error: 'workout_deleted' }
  } else {
    const range = isRecord(workout.heartRateRange) ? workout.heartRateRange : null
    await env.DB.batch([
      env.DB.prepare(
        'INSERT INTO watch_v3_workouts '
          + '(owner_id, workout_id, payload_hash, payload_json, started_at, ended_at, duration_ms, '
          + 'distance_meters, steps, average_heart_rate, maximum_heart_rate, minimum_heart_rate, created_at, '
          + 'origin_device_id, created_operation_id) '
          + 'SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? '
          + 'WHERE NOT EXISTS (SELECT 1 FROM watch_v3_workout_tombstones WHERE owner_id = ? AND workout_id = ?) '
          + 'ON CONFLICT(owner_id, workout_id) DO NOTHING',
      ).bind(
        OWNER_ID, workout.id, payloadHash, JSON.stringify(workout), String(workout.startedAt),
        String(workout.endedAt), workout.durationMs, workout.distanceMeters, workout.steps,
        workout.averageHeartRate, range?.max ?? null, range?.min ?? null, timestamp, deviceId, operationId,
        OWNER_ID, workout.id,
      ),
      env.DB.prepare(
        'INSERT OR IGNORE INTO watch_v3_changes '
          + '(owner_id, change_type, entity_id, entity_revision, operation, payload_json, changed_at, origin_device_id, operation_id) '
          + "SELECT ?, 'workout', ?, 1, 'upsert', ?, ?, ?, ? WHERE EXISTS "
          + '(SELECT 1 FROM watch_v3_workouts WHERE owner_id = ? AND workout_id = ? AND created_operation_id = ?)',
      ).bind(
        OWNER_ID, workout.id, JSON.stringify(workout), timestamp, deviceId, operationId,
        OWNER_ID, workout.id, operationId,
      ),
    ])
    const [stored, deleted] = await Promise.all([
      env.DB.prepare(
        'SELECT payload_hash, tombstoned, created_operation_id FROM watch_v3_workouts '
          + 'WHERE owner_id = ? AND workout_id = ?',
      ).bind(OWNER_ID, workout.id).first<{
        payload_hash: string; tombstoned: number | string; created_operation_id: string
      }>(),
      env.DB.prepare(
        'SELECT command_id FROM watch_v3_workout_tombstones WHERE owner_id = ? AND workout_id = ?',
      ).bind(OWNER_ID, workout.id).first<{ command_id: string }>(),
    ])
    result = deleted || Number(stored?.tombstoned ?? 0) === 1
      ? { operationId, outcome: 'conflict', workoutId: workout.id, error: 'workout_deleted' }
      : stored?.payload_hash === payloadHash
        ? {
            operationId, outcome: 'acknowledged', workoutId: workout.id,
            revision: 1, ...(stored.created_operation_id === operationId ? {} : { replayed: true }),
          }
        : { operationId, outcome: 'conflict', workoutId: workout.id, error: 'workout_immutable' }
  }
  await saveOperation(env, deviceId, operationId, 'workout_fact', requestHash, result, timestamp)
  return result
}

async function applySleepRecord(
  env: CloudV3Env, deviceId: string, item: JsonRecord, timestamp: string,
): Promise<JsonRecord> {
  const requestHash = await sha256(stableJson(item))
  const operationId = String(item.operationId)
  const replay = await operationReplay(env, operationId, requestHash)
  if (replay === 'reused') return { operationId, outcome: 'conflict', error: 'operation_id_reused' }
  if (replay) return { ...replay, replayed: true }
  const record = item.record as JsonRecord
  const payloadHash = await sha256(stableJson(record))
  const existing = await env.DB.prepare(
    'SELECT source_revision, payload_hash FROM watch_v3_sleep_records WHERE owner_id = ? AND record_id = ?',
  ).bind(OWNER_ID, item.recordId).first<{ source_revision: string; payload_hash: string }>()
  let result: JsonRecord
  if (existing && existing.source_revision === item.sourceRevision && existing.payload_hash === payloadHash) {
    result = { operationId, outcome: 'acknowledged', recordId: item.recordId, replayed: true }
  } else {
    const sessions = record.sessions as JsonRecord[]
    const start = sessions.length ? Math.min(...sessions.map((session) => Number(session.startTime))) : Number(record.timestamp)
    const end = sessions.length ? Math.max(...sessions.map((session) => Number(session.endTime))) : Number(record.timestamp)
    await env.DB.batch([
      env.DB.prepare(
        'INSERT INTO watch_v3_sleep_records '
          + '(owner_id, record_id, source_revision, payload_hash, payload_json, start_time, end_time, updated_at, origin_device_id) '
          + 'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(owner_id, record_id) DO UPDATE SET '
          + 'source_revision = excluded.source_revision, payload_hash = excluded.payload_hash, '
          + 'payload_json = excluded.payload_json, start_time = excluded.start_time, end_time = excluded.end_time, '
          + 'updated_at = excluded.updated_at, origin_device_id = excluded.origin_device_id',
      ).bind(OWNER_ID, item.recordId, item.sourceRevision, payloadHash, JSON.stringify(record), String(start), String(end), timestamp, deviceId),
      env.DB.prepare(
        'INSERT INTO watch_v3_changes '
          + '(owner_id, change_type, entity_id, entity_revision, operation, payload_json, changed_at, origin_device_id, operation_id) '
          + "VALUES (?, 'sleep', ?, ?, 'upsert', ?, ?, ?, ?)",
      ).bind(OWNER_ID, item.recordId, Number(record.timestamp), JSON.stringify(record), timestamp, deviceId, operationId),
    ])
    result = { operationId, outcome: 'acknowledged', recordId: item.recordId, sourceRevision: item.sourceRevision }
  }
  await saveOperation(env, deviceId, operationId, 'sleep_record', requestHash, result, timestamp)
  return result
}

async function applyLiveStatus(env: CloudV3Env, deviceId: string, status: JsonRecord, timestamp: string): Promise<void> {
  await env.DB.prepare(
    'INSERT INTO watch_v3_live_status '
      + '(owner_id, device_id, status_revision, payload_json, observed_at, expires_at, updated_at) '
      + 'VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(owner_id) DO UPDATE SET '
      + 'device_id = excluded.device_id, status_revision = excluded.status_revision, payload_json = excluded.payload_json, '
      + 'observed_at = excluded.observed_at, expires_at = excluded.expires_at, updated_at = excluded.updated_at '
      + 'WHERE excluded.status_revision >= watch_v3_live_status.status_revision',
  ).bind(
    OWNER_ID, deviceId, status.statusRevision, JSON.stringify(status),
    String(status.observedAt), String(status.expiresAt), timestamp,
  ).run()
}

async function expireCommands(db: D1Database, timestamp: string): Promise<void> {
  const expired = await db.prepare(
    "SELECT command_id FROM watch_v3_commands WHERE owner_id = ? AND status IN ('pending', 'delivered') AND expires_at <= ?",
  ).bind(OWNER_ID, timestamp).all<{ command_id: string }>()
  if (!expired.results.length) return
  await db.batch(expired.results.flatMap((row) => [
    db.prepare(
      "UPDATE watch_v3_commands SET status = 'expired', completed_at = ? WHERE owner_id = ? AND command_id = ? AND status IN ('pending', 'delivered')",
    ).bind(timestamp, OWNER_ID, row.command_id),
    db.prepare(
      "INSERT INTO watch_v3_command_audit (owner_id, command_id, event, event_at, detail_json) VALUES (?, ?, 'expired', ?, '{}')",
    ).bind(OWNER_ID, row.command_id, timestamp),
  ]))
}

async function applyCommandResult(env: CloudV3Env, result: JsonRecord, timestamp: string): Promise<JsonRecord> {
  const command = await env.DB.prepare(
    'SELECT status, expires_at, command_type, arguments_json FROM watch_v3_commands WHERE owner_id = ? AND command_id = ?',
  ).bind(OWNER_ID, result.commandId).first<{
    status: string; expires_at: string; command_type: string; arguments_json: string
  }>()
  if (!command) return { commandId: result.commandId, outcome: 'rejected', error: 'unknown_command' }
  if (command.status === 'succeeded' || command.status === 'failed') {
    return { commandId: result.commandId, outcome: 'acknowledged', replayed: true }
  }
  if (command.status === 'expired' || command.expires_at <= timestamp) {
    await env.DB.prepare(
      "INSERT INTO watch_v3_command_audit (owner_id, command_id, event, event_at, detail_json) VALUES (?, ?, 'late_result_rejected', ?, '{}')",
    ).bind(OWNER_ID, result.commandId, timestamp).run()
    return { commandId: result.commandId, outcome: 'rejected', error: 'command_expired' }
  }
  const status = result.outcome === 'succeeded' ? 'succeeded' : 'failed'
  await env.DB.batch([
    env.DB.prepare(
      'UPDATE watch_v3_commands SET status = ?, completed_at = ?, result_json = ? '
        + "WHERE owner_id = ? AND command_id = ? AND status IN ('pending', 'delivered')",
    ).bind(status, timestamp, JSON.stringify(result), OWNER_ID, result.commandId),
    env.DB.prepare(
      'INSERT INTO watch_v3_command_audit (owner_id, command_id, event, event_at, detail_json) VALUES (?, ?, ?, ?, ?)',
    ).bind(OWNER_ID, result.commandId, status === 'succeeded' ? 'acknowledged' : 'failed', timestamp, JSON.stringify({ actualState: result.actualState })),
  ])
  if (status === 'succeeded' && command.command_type === 'delete_workout') {
    const args = JSON.parse(command.arguments_json) as JsonRecord
    await env.DB.batch([
      env.DB.prepare(
        'INSERT INTO watch_v3_workout_tombstones (owner_id, workout_id, command_id, deleted_at) '
          + 'VALUES (?, ?, ?, ?) ON CONFLICT(owner_id, workout_id) DO NOTHING',
      ).bind(OWNER_ID, args.workoutId, result.commandId, timestamp),
      env.DB.prepare(
        'UPDATE watch_v3_workouts SET tombstoned = 1, tombstoned_at = ?, tombstone_command_id = ? '
          + 'WHERE owner_id = ? AND workout_id = ? AND tombstoned = 0',
      ).bind(timestamp, result.commandId, OWNER_ID, args.workoutId),
      env.DB.prepare(
        'INSERT INTO watch_v3_changes '
          + '(owner_id, change_type, entity_id, entity_revision, operation, payload_json, changed_at, operation_id) '
          + "VALUES (?, 'workout_tombstone', ?, 2, 'delete', NULL, ?, ?)",
      ).bind(OWNER_ID, args.workoutId, timestamp, result.commandId),
    ])
  }
  return { commandId: result.commandId, outcome: 'acknowledged' }
}

async function pendingCommands(env: CloudV3Env, timestamp: string): Promise<JsonRecord[]> {
  const rows = await env.DB.prepare(
    "SELECT command_id, request_id, command_type, expected_state, control_revision, arguments_json, created_at, expires_at "
      + "FROM watch_v3_commands WHERE owner_id = ? AND status IN ('pending', 'delivered') AND expires_at > ? "
      + 'ORDER BY created_at LIMIT 25',
  ).bind(OWNER_ID, timestamp).all<{
    command_id: string; request_id: string; command_type: string; expected_state: string | null
    control_revision: number | string; arguments_json: string; created_at: string; expires_at: string
  }>()
  if (rows.results.length) {
    await env.DB.batch(rows.results.flatMap((row) => [
      env.DB.prepare(
        "UPDATE watch_v3_commands SET status = 'delivered', delivered_at = COALESCE(delivered_at, ?) "
          + "WHERE owner_id = ? AND command_id = ? AND status = 'pending'",
      ).bind(timestamp, OWNER_ID, row.command_id),
      env.DB.prepare(
        "INSERT INTO watch_v3_command_audit (owner_id, command_id, event, event_at, detail_json) VALUES (?, ?, 'delivered', ?, '{}')",
      ).bind(OWNER_ID, row.command_id, timestamp),
    ]))
  }
  return rows.results.map((row) => ({
    commandId: row.command_id,
    requestId: row.request_id,
    type: row.command_type,
    expectedState: row.expected_state,
    controlRevision: Number(row.control_revision),
    arguments: JSON.parse(row.arguments_json),
    createdAt: row.created_at,
    expiresAt: row.expires_at,
  }))
}

async function readChanges(env: CloudV3Env, cursor: number): Promise<{ changes: JsonRecord[]; nextCursor: string; hasMore: boolean }> {
  const rows = await env.DB.prepare(
    'SELECT change_seq, change_type, entity_id, entity_revision, operation, payload_json, changed_at '
      + 'FROM watch_v3_changes WHERE owner_id = ? AND change_seq > ? ORDER BY change_seq LIMIT ?',
  ).bind(OWNER_ID, cursor, MAX_CHANGES + 1).all<{
    change_seq: number | string; change_type: string; entity_id: string; entity_revision: number | string
    operation: string; payload_json: string | null; changed_at: string
  }>()
  const visible = rows.results.slice(0, MAX_CHANGES)
  const next = visible.length ? Number(visible[visible.length - 1].change_seq) : cursor
  return {
    changes: visible.map((row) => ({
      sequence: Number(row.change_seq), type: row.change_type, entityId: row.entity_id,
      revision: Number(row.entity_revision), operation: row.operation,
      payload: row.payload_json ? JSON.parse(row.payload_json) : null, changedAt: row.changed_at,
    })),
    nextCursor: encodeCursor(next),
    hasMore: rows.results.length > MAX_CHANGES,
  }
}

async function handleExchange(request: Request, env: CloudV3Env): Promise<Response> {
  if (!cloudV3Configured(env)) return errorJson('revision_domain_not_configured', 503)
  const revisionDomainId = env.WATCH_PLAN_REVISION_DOMAIN_ID!
  const device = await authenticateDevice(request, env)
  if (!device) return errorJson('unauthorized', 401)
  let raw: unknown
  try { raw = await readJson(request) } catch (caught) {
    const code = caught instanceof Error ? caught.message : 'invalid_json'
    return errorJson(code, code === 'payload_too_large' ? 413 : code === 'unsupported_media_type' ? 415 : 400)
  }
  const exchange = parseExchange(raw)
  if (!exchange) return errorJson('invalid_exchange', 400)
  if (exchange.deviceId !== device.device_id) return errorJson('device_mismatch', 403)
  const timestamp = new Date().toISOString()
  await deviceState(env, device.device_id, timestamp)
  const latest = await env.DB.prepare(
    'SELECT COALESCE(MAX(change_seq), 0) AS sequence FROM watch_v3_changes WHERE owner_id = ?',
  ).bind(OWNER_ID).first<{ sequence: number | string }>()
  if (exchange.cursor > Number(latest?.sequence ?? 0)) {
    const latestCursor = encodeCursor(Number(latest?.sequence ?? 0))
    return errorJson('cursor_ahead', 409, { latestCursor, resetCursor: latestCursor })
  }
  const requestHash = await sha256(stableJson(raw))
  const replay = await operationReplay(env, exchange.requestId, requestHash)
  if (replay === 'reused') return errorJson('request_id_reused', 409)
  if (replay) return json({ ...replay, revisionDomainId, replayed: true })
  const acknowledgements: JsonRecord[] = []
  for (const change of exchange.planChanges) acknowledgements.push(await applyPlanChange(env, device.device_id, change, timestamp))
  for (const fact of exchange.workoutFacts) acknowledgements.push(await applyWorkoutFact(env, device.device_id, fact, timestamp))
  for (const item of exchange.sleepRecords) acknowledgements.push(await applySleepRecord(env, device.device_id, item, timestamp))
  if (exchange.liveStatus) await applyLiveStatus(env, device.device_id, exchange.liveStatus, timestamp)
  await expireCommands(env.DB, timestamp)
  const commandAcknowledgements: JsonRecord[] = []
  for (const result of exchange.commandResults) commandAcknowledgements.push(await applyCommandResult(env, result, timestamp))
  const feed = await readChanges(env, exchange.cursor)
  const commands = await pendingCommands(env, timestamp)
  const planLibrary = await loadPlanLibrary(env.DB)
  await env.DB.prepare(
    'UPDATE watch_v3_device_state SET cursor = ?, last_exchange_at = ?, '
      + 'last_status_at = CASE WHEN ? = 1 THEN ? ELSE last_status_at END, updated_at = ? WHERE device_id = ?',
  ).bind(
    decodeCursor(feed.nextCursor), timestamp, exchange.liveStatus ? 1 : 0, timestamp, timestamp, device.device_id,
  ).run()
  const response: JsonRecord = {
    protocolVersion: 3,
    authority: 'cloud_authoritative',
    revisionDomainId,
    requestId: exchange.requestId,
    acknowledgements,
    commandAcknowledgements,
    planLibrary,
    changes: feed.changes,
    pendingCommands: commands,
    nextCursor: feed.nextCursor,
    hasMore: feed.hasMore,
    serverTime: timestamp,
  }
  await saveOperation(env, device.device_id, exchange.requestId, 'exchange', requestHash, response, timestamp)
  return json(response)
}

async function handleChannel(request: Request, env: CloudV3Env): Promise<Response> {
  if (request.headers.get('upgrade')?.toLowerCase() !== 'websocket') return errorJson('websocket_required', 426)
  const device = await authenticateDevice(request, env)
  if (!device) return errorJson('unauthorized', 401)
  if (!env.COMMAND_CHANNEL) return errorJson('command_channel_unavailable', 503)
  const state = await env.DB.prepare(
    'SELECT owner_id FROM watch_v3_device_state WHERE device_id = ?',
  ).bind(device.device_id).first<{ owner_id: string }>()
  if (!state || state.owner_id !== OWNER_ID) return errorJson('v3_exchange_required', 409)
  const stub = env.COMMAND_CHANNEL.get(env.COMMAND_CHANNEL.idFromName(OWNER_ID))
  const headers = new Headers(request.headers)
  headers.delete('authorization')
  headers.set('x-watch-device-id', device.device_id)
  return stub.fetch(new Request('https://channel/connect', { headers }))
}

export async function routeCloudV3(request: Request, env: CloudV3Env): Promise<Response | null> {
  const url = new URL(request.url)
  if (url.pathname === '/sync/v3/exchange' && request.method === 'POST') return handleExchange(request, env)
  if (url.pathname === '/sync/v3/channel' && request.method === 'GET') return handleChannel(request, env)
  return null
}

export class WatchCommandChannel implements DurableObject {
  constructor(private readonly state: DurableObjectState) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url)
    if (url.pathname === '/connect') {
      if (request.headers.get('upgrade')?.toLowerCase() !== 'websocket') return new Response('upgrade required', { status: 426 })
      const pair = new WebSocketPair()
      const [client, server] = Object.values(pair) as [WebSocket, WebSocket]
      this.state.acceptWebSocket(server)
      server.send(JSON.stringify({ type: 'connected' }))
      return new Response(null, { status: 101, webSocket: client })
    }
    if (url.pathname === '/notify' && request.method === 'POST') {
      const payload = JSON.stringify({ type: 'sync_needed' })
      for (const socket of this.state.getWebSockets()) {
        try { socket.send(payload) } catch { try { socket.close(1011, 'send failed') } catch {} }
      }
      return new Response(null, { status: 204 })
    }
    return new Response('not found', { status: 404 })
  }
}

async function notifySyncNeeded(env: CloudV3Env): Promise<void> {
  if (!env.COMMAND_CHANNEL) return
  const stub = env.COMMAND_CHANNEL.get(env.COMMAND_CHANNEL.idFromName(OWNER_ID))
  await stub.fetch('https://channel/notify', { method: 'POST' })
}

export async function createCommand(
  env: CloudV3Env,
  input: {
    requestId: string
    commandId: string
    type: 'start' | 'pause' | 'resume' | 'stop' | 'select_plan' | 'delete_workout'
    expectedState: string | null
    controlRevision: number
    arguments: JsonRecord
  },
  waitMs = CONTROL_WAIT_MS,
): Promise<JsonRecord> {
  if (!validUuid(input.requestId) || !validUuid(input.commandId) || !safeInteger(input.controlRevision)
    || forbiddenField(input.arguments)) throw new Error('invalid_command')
  const requestHash = await sha256(stableJson(input))
  const createdAt = new Date()
  const expiresAt = new Date(createdAt.getTime() + COMMAND_TTL_MS)
  await expireCommands(env.DB, createdAt.toISOString())
  await env.DB.batch([
    env.DB.prepare(
      'INSERT OR IGNORE INTO watch_v3_commands '
        + '(owner_id, command_id, request_id, command_type, expected_state, control_revision, arguments_json, '
        + 'request_hash, status, created_at, expires_at) '
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)",
    ).bind(
      OWNER_ID, input.commandId, input.requestId, input.type, input.expectedState,
      input.controlRevision, JSON.stringify(input.arguments), requestHash,
      createdAt.toISOString(), expiresAt.toISOString(),
    ),
    env.DB.prepare(
      'INSERT OR IGNORE INTO watch_v3_command_audit (owner_id, command_id, event, event_at, detail_json) '
        + "SELECT ?, ?, 'created', ?, '{}' WHERE EXISTS "
        + '(SELECT 1 FROM watch_v3_commands WHERE owner_id = ? AND command_id = ? AND request_hash = ?)',
    ).bind(
      OWNER_ID, input.commandId, createdAt.toISOString(),
      OWNER_ID, input.commandId, requestHash,
    ),
    env.DB.prepare(
      'INSERT OR IGNORE INTO watch_v3_changes '
        + '(owner_id, change_type, entity_id, entity_revision, operation, payload_json, changed_at, operation_id) '
        + "SELECT ?, 'command', ?, ?, 'upsert', ?, ?, ? WHERE EXISTS "
        + '(SELECT 1 FROM watch_v3_commands WHERE owner_id = ? AND command_id = ? AND request_hash = ?)',
    ).bind(
      OWNER_ID, input.commandId, input.controlRevision,
      JSON.stringify({ commandId: input.commandId }), createdAt.toISOString(), input.requestId,
      OWNER_ID, input.commandId, requestHash,
    ),
  ])
  const existing = await env.DB.prepare(
    'SELECT command_id, request_id, request_hash, status, expires_at, result_json '
      + 'FROM watch_v3_commands WHERE owner_id = ? AND (request_id = ? OR command_id = ?)',
  ).bind(OWNER_ID, input.requestId, input.commandId).first<{
    command_id: string; request_id: string; request_hash: string
    status: string; expires_at: string; result_json: string | null
  }>()
  if (!existing || existing.command_id !== input.commandId || existing.request_id !== input.requestId
    || existing.request_hash !== requestHash) throw new Error('request_id_reused')
  await notifySyncNeeded(env)
  const deadline = Date.now() + Math.min(CONTROL_WAIT_MS, Math.max(0, waitMs))
  while (Date.now() < deadline) {
    const row = await env.DB.prepare(
      'SELECT status, expires_at, result_json FROM watch_v3_commands WHERE owner_id = ? AND command_id = ?',
    ).bind(OWNER_ID, input.commandId).first<{ status: string; expires_at: string; result_json: string | null }>()
    if (row?.status === 'succeeded' || row?.status === 'failed' || row?.status === 'expired') {
      return {
        commandId: input.commandId, requestId: input.requestId, status: row.status,
        expiresAt: row.expires_at, result: row.result_json ? JSON.parse(row.result_json) : null,
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  return {
    commandId: input.commandId, requestId: input.requestId,
    status: existing.status === 'delivered' ? 'pending' : existing.status,
    expiresAt: existing.expires_at,
  }
}

export async function getCommand(env: CloudV3Env, commandId: string): Promise<JsonRecord | null> {
  if (!validUuid(commandId)) return null
  await expireCommands(env.DB, new Date().toISOString())
  const row = await env.DB.prepare(
    'SELECT command_id, request_id, command_type, expected_state, control_revision, status, created_at, '
      + 'expires_at, delivered_at, completed_at, result_json FROM watch_v3_commands WHERE owner_id = ? AND command_id = ?',
  ).bind(OWNER_ID, commandId).first<Record<string, string | number | null>>()
  if (!row) return null
  return {
    commandId: row.command_id, requestId: row.request_id, type: row.command_type,
    expectedState: row.expected_state, controlRevision: Number(row.control_revision), status: row.status,
    createdAt: row.created_at, expiresAt: row.expires_at, deliveredAt: row.delivered_at,
    completedAt: row.completed_at, result: row.result_json ? JSON.parse(String(row.result_json)) : null,
  }
}

export async function cloudStatus(db: D1Database): Promise<JsonRecord> {
  const [status, devices, library] = await Promise.all([
    db.prepare('SELECT payload_json, observed_at, expires_at, updated_at FROM watch_v3_live_status WHERE owner_id = ?')
      .bind(OWNER_ID).first<{ payload_json: string; observed_at: string; expires_at: string; updated_at: string }>(),
    db.prepare('SELECT COUNT(*) AS count, MAX(last_exchange_at) AS last_exchange_at FROM watch_v3_device_state WHERE owner_id = ?')
      .bind(OWNER_ID).first<{ count: number | string; last_exchange_at: string | null }>(),
    loadPlanLibrary(db),
  ])
  const now = Date.now()
  return {
    authority: 'cloud_authoritative',
    supportsPcOff: false,
    freshness: status ? (Number(status.expires_at) > now ? 'fresh' : 'stale') : 'unknown',
    observedAt: status?.observed_at ?? null,
    expiresAt: status?.expires_at ?? null,
    lastExchangeAt: devices?.last_exchange_at ?? null,
    activeDeviceCount: Number(devices?.count ?? 0),
    planRevision: Number(library.revision),
    status: status ? JSON.parse(status.payload_json) : null,
  }
}

export async function cloudPlans(db: D1Database): Promise<JsonRecord> {
  return loadPlanLibrary(db)
}

export async function replaceCloudPlanLibrary(
  env: CloudV3Env, deviceId: string | null, operationId: string, expectedRevision: number, library: PlanLibrary,
): Promise<JsonRecord> {
  if (!validUuid(operationId) || !validLibrary(library)) throw new Error('invalid_plan_library')
  return applyPlanChange(env, deviceId, { operationId, expectedRevision, library }, new Date().toISOString())
}

export async function cloudWorkouts(db: D1Database, limit = 100): Promise<JsonRecord> {
  const rows = await db.prepare(
    'SELECT payload_json FROM watch_v3_workouts WHERE owner_id = ? AND tombstoned = 0 ORDER BY started_at DESC LIMIT ?',
  ).bind(OWNER_ID, Math.max(1, Math.min(200, limit))).all<{ payload_json: string }>()
  return { workouts: rows.results.map((row) => JSON.parse(row.payload_json)), rawRoute: 'local_only', heartRateSamples: 'local_only' }
}

export async function cloudWorkout(db: D1Database, workoutId: string): Promise<JsonRecord | null> {
  if (!validId(workoutId)) return null
  const row = await db.prepare(
    'SELECT payload_json FROM watch_v3_workouts WHERE owner_id = ? AND workout_id = ? AND tombstoned = 0',
  ).bind(OWNER_ID, workoutId).first<{ payload_json: string }>()
  if (!row) return null
  const workout = JSON.parse(row.payload_json) as JsonRecord
  const range = isRecord(workout.heartRateRange) ? workout.heartRateRange : null
  return {
    ...workout,
    minimumHeartRate: range?.min ?? null,
    maximumHeartRate: range?.max ?? null,
    rawRoute: 'local_only',
    heartRateSamples: 'local_only',
  }
}

export async function summarizeCloudWorkouts(db: D1Database): Promise<JsonRecord> {
  const row = await db.prepare(
    'SELECT COUNT(*) AS workout_count, COALESCE(SUM(duration_ms), 0) AS duration_ms, '
      + 'COALESCE(SUM(distance_meters), 0) AS distance_meters, COALESCE(SUM(steps), 0) AS steps '
      + 'FROM watch_v3_workouts WHERE owner_id = ? AND tombstoned = 0',
  ).bind(OWNER_ID).first<Record<string, number | string>>()
  return {
    workoutCount: Number(row?.workout_count ?? 0), totalDurationMs: Number(row?.duration_ms ?? 0),
    totalDistanceMeters: Number(row?.distance_meters ?? 0), totalSteps: Number(row?.steps ?? 0),
  }
}

export async function cloudSleepRecords(db: D1Database, limit = 31): Promise<JsonRecord> {
  const rows = await db.prepare(
    'SELECT record_id, source_revision, payload_json, updated_at FROM watch_v3_sleep_records '
      + 'WHERE owner_id = ? ORDER BY end_time DESC LIMIT ?',
  ).bind(OWNER_ID, Math.max(1, Math.min(31, limit))).all<{
    record_id: string; source_revision: string; payload_json: string; updated_at: string
  }>()
  return { records: rows.results.map((row) => ({
    recordId: row.record_id, sourceRevision: row.source_revision,
    record: JSON.parse(row.payload_json), updatedAt: row.updated_at,
  })) }
}

export async function summarizeCloudSleep(db: D1Database): Promise<JsonRecord> {
  const records = await cloudSleepRecords(db, 31) as { records: Array<{ record: JsonRecord }> }
  // Blank days (no session and zero duration) are not sleep nights; count them separately so a
  // monthly average is not dragged down by "0分/0分" placeholder records from the device.
  const meaningful = records.records.filter((item) => {
    const record = item.record
    const sessions = Array.isArray(record.sessions) ? record.sessions : []
    return sessions.length > 0 || Number(record.totalDurationMinutes ?? 0) > 0
  })
  const empty = records.records.length - meaningful.length
  const total = meaningful.reduce((sum, item) => sum + Number(item.record.totalDurationMinutes ?? 0), 0)
  const scores = meaningful.map((item) => Number(item.record.sleepScore)).filter(Number.isFinite)
  return {
    recordCount: records.records.length,
    nightsWithData: meaningful.length,
    emptyRecordCount: empty,
    totalDurationMinutes: total,
    averageScore: scores.length ? scores.reduce((sum, score) => sum + score, 0) / scores.length : null,
  }
}
