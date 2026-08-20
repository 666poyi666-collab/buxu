export interface SyncEnv {
  DB: D1Database
  SYNC_KEY?: string
  /** Test-only switch for exercising the retired plaintext contract. Never configure remotely. */
  ALLOW_LEGACY_SYNC_V1?: string
}

import {
  encryptedStatus,
  exchangeEncrypted,
  isValidWatchEncryptedEntity,
  parseEncryptedExchange,
} from './encrypted-sync'
import { parseReadProjection, replaceReadProjection } from './read-projection'

type JsonRecord = Record<string, unknown>
type EntityType = 'plan' | 'workout'
type Operation = 'upsert' | 'delete'

type EntityRow = {
  entity_type: EntityType
  entity_id: string
  revision: number | string
  deleted: number | string
  payload_json: string | null
  created_at: string
  updated_at: string
  deleted_at: string | null
  origin_device_id: string
  last_operation_id: string
}

type DeviceRow = {
  device_id: string
  label: string | null
  token_hash: string
  created_at: string
  revoked_at: string | null
  last_successful_exchange_at: string | null
  last_successful_push_at: string | null
  last_successful_pull_at: string | null
  last_cursor: string | null
}

type OperationRow = {
  op_id: string
  device_id: string
  request_hash: string
  reservation_id: string
  result_json: string | null
  created_at: string
  completed_at: string | null
}

type Mutation = {
  opId: string
  entityType: EntityType
  entityId: string
  baseRevision: number
  operation: Operation
  payload: JsonRecord | null
}

type EntityState = {
  entityType: EntityType
  entityId: string
  revision: number
  operation: Operation
  payload: JsonRecord | null
  deletedAt: string | null
  updatedAt: string
}

type Acknowledgement = {
  outcome: 'acknowledged'
  opId: string
  entityType: EntityType
  entityId: string
  operation: Operation
  revision: number
  replayed?: boolean
}

type Conflict = {
  outcome: 'conflict'
  opId: string
  entityType: EntityType
  entityId: string
  operation: Operation
  error: string
  retryable?: boolean
  current: EntityState | null
  candidate: JsonRecord | null
  conflictId?: string
  preserveCandidate?: boolean
}

type MutationResult = Acknowledgement | Conflict

const REQUEST_KEYS = new Set(['protocolVersion', 'deviceId', 'cursor', 'mutations'])
const MUTATION_KEYS = new Set(['opId', 'entityType', 'entityId', 'baseRevision', 'operation', 'payload'])
const PLAN_KEYS = new Set([
  'schemaVersion', 'id', 'name', 'groupId', 'groupName', 'groupSortOrder',
  'requirement', 'updatedAt', 'selected', 'stages',
])
const STAGE_KEYS = new Set(['kind', 'unit', 'target'])
const WORKOUT_KEYS = new Set([
  'id', 'schemaVersion', 'startedAt', 'endedAt', 'durationMs', 'pausedDurationMs',
  'distanceMeters', 'steps', 'averageHeartRate', 'planName', 'planGroup',
  'planCompletedActiveMs', 'planDistanceMeters', 'freeRecordingDistanceMeters',
  'stageResults', 'averagePaceSecondsPerKm', 'averageCadenceSpm',
  'maxSmoothedSpeedMps', 'detailRefs',
])
const WORKOUT_REQUIRED_KEYS = new Set([
  'id', 'schemaVersion', 'startedAt', 'endedAt', 'durationMs', 'pausedDurationMs',
  'distanceMeters', 'steps', 'averageHeartRate', 'planName', 'planGroup',
  'planCompletedActiveMs', 'planDistanceMeters', 'freeRecordingDistanceMeters',
  'stageResults',
])
const STAGE_RESULT_KEYS = new Set([
  'index', 'name', 'unit', 'target', 'completedAtMs', 'totalDistanceMeters',
])
const DETAIL_REF_KEYS = new Set(['route', 'heartRate'])
const OBJECT_REF_KEYS = new Set(['storage', 'key', 'sha256', 'sizeBytes', 'contentType', 'encryption'])
const ENCRYPTION_KEYS = new Set(['algorithm', 'keyId'])

const MAX_EXCHANGE_BYTES = 1024 * 1024
const MAX_ADMIN_BYTES = 16 * 1024
const MAX_MUTATIONS = 25
const MAX_PAYLOAD_CHARS = 128_000
const CHANGE_PAGE_SIZE = 100
const OPERATION_RESERVATION_MS = 30_000
const DEVICE_TOKEN_PREFIX = 'dw1'
const ENCRYPTED_SYNC_PRODUCT = 'watch'

const nowIso = () => new Date().toISOString()

function isRecord(value: unknown): value is JsonRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function hasExactKeys(value: JsonRecord, keys: ReadonlySet<string>): boolean {
  const actual = Object.keys(value)
  return actual.length === keys.size && actual.every((key) => keys.has(key))
}

function hasOnlyKeys(value: JsonRecord, keys: ReadonlySet<string>): boolean {
  return Object.keys(value).every((key) => keys.has(key))
}

function validDeviceId(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{2,127}$/.test(value)
}

function validEntityId(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value)
}

function validUuid(value: unknown): value is string {
  return typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
}

function nonNegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0
}

function finiteNonNegative(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
}

function boundedString(value: unknown, maximum: number, allowEmpty = true): value is string {
  return typeof value === 'string' && value.length <= maximum && (allowEmpty || value.trim().length > 0)
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

function randomBase64Url(bytes = 32): string {
  const values = crypto.getRandomValues(new Uint8Array(bytes))
  let binary = ''
  for (const value of values) binary += String.fromCharCode(value)
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
}

function bearerToken(request: Request): string | null {
  const match = /^Bearer\s+([^\s]+)$/i.exec(request.headers.get('authorization') ?? '')
  return match?.[1] ?? null
}

function tokenDeviceId(token: string | null): string | null {
  if (!token) return null
  const match = /^dw1\.([A-Za-z0-9][A-Za-z0-9_-]{2,127})\.([A-Za-z0-9_-]{32,})$/.exec(token)
  return match?.[1] ?? null
}

function encodeCursor(sequence: number): string {
  return `c${Math.max(0, Math.trunc(sequence)).toString(36)}`
}

function decodeCursor(value: unknown): number | null {
  if (value === null) return 0
  if (typeof value !== 'string' || !/^c[0-9a-z]+$/i.test(value)) return null
  const sequence = Number.parseInt(value.slice(1), 36)
  return Number.isSafeInteger(sequence) && sequence >= 0 ? sequence : null
}

function responseHeaders(): Headers {
  return new Headers({
    'Cache-Control': 'no-store',
    'Content-Type': 'application/json; charset=UTF-8',
    Vary: 'Authorization',
  })
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: responseHeaders() })
}

function errorJson(error: string, status: number, details?: JsonRecord): Response {
  return json({ error, ...details }, status)
}

async function readJson(request: Request, limit: number): Promise<unknown> {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get('content-type') ?? '')) {
    throw new Error('unsupported_media_type')
  }
  const bytes = await request.arrayBuffer()
  if (bytes.byteLength > limit) throw new Error('payload_too_large')
  try {
    return JSON.parse(new TextDecoder().decode(bytes))
  } catch {
    throw new Error('invalid_json')
  }
}

function looksLikeRawBase64(value: string): boolean {
  if (/^data:/i.test(value.trim())) return true
  const compact = value.replace(/\s/g, '')
  if (compact.length < 128) return false
  return compact.length % 4 === 0 && /^[A-Za-z0-9+/]+={0,2}$/.test(compact)
    || /^[A-Za-z0-9_-]+$/.test(compact)
}

function stringsAreSafe(value: unknown, depth = 0): boolean {
  if (depth > 32) return false
  if (typeof value === 'string') return !looksLikeRawBase64(value)
  if (value === null || typeof value === 'boolean') return true
  if (typeof value === 'number') return Number.isFinite(value)
  if (Array.isArray(value)) return value.length <= 10_000 && value.every((item) => stringsAreSafe(item, depth + 1))
  if (!isRecord(value)) return false
  return Object.values(value).every((child) => stringsAreSafe(child, depth + 1))
}

function cloneWithinLimit(value: JsonRecord): JsonRecord | null {
  try {
    const serialized = JSON.stringify(value)
    if (serialized.length > MAX_PAYLOAD_CHARS) return null
    const copy = JSON.parse(serialized)
    return isRecord(copy) ? copy : null
  } catch {
    return null
  }
}

function parsePlan(value: unknown, entityId: string): JsonRecord | null {
  if (!isRecord(value) || !hasExactKeys(value, PLAN_KEYS) || !stringsAreSafe(value)) return null
  if (value.schemaVersion !== 1 || value.id !== entityId) return null
  if (!boundedString(value.name, 120, false) || !boundedString(value.groupId, 128, false)) return null
  if (!boundedString(value.groupName, 120) || !boundedString(value.requirement, 2_000)) return null
  if (!nonNegativeInteger(value.groupSortOrder) || !nonNegativeInteger(value.updatedAt)) return null
  if (typeof value.selected !== 'boolean') return null
  if (!Array.isArray(value.stages) || value.stages.length < 1 || value.stages.length > 256) return null
  for (const stage of value.stages) {
    if (!isRecord(stage) || !hasExactKeys(stage, STAGE_KEYS)) return null
    if (!['RUN', 'WALK', 'REST'].includes(String(stage.kind))) return null
    if (!['DISTANCE', 'TIME'].includes(String(stage.unit))) return null
    if (!Number.isSafeInteger(stage.target) || Number(stage.target) < 1 || Number(stage.target) > 1_000_000) return null
  }
  return cloneWithinLimit(value)
}

function validEncryption(value: unknown): boolean {
  return isRecord(value)
    && hasExactKeys(value, ENCRYPTION_KEYS)
    && value.algorithm === 'AES-256-GCM'
    && boundedString(value.keyId, 128, false)
}

function validObjectRef(value: unknown): boolean {
  if (!isRecord(value) || !hasExactKeys(value, OBJECT_REF_KEYS)) return false
  if (value.storage !== 'r2' || !boundedString(value.key, 512, false)) return false
  if (value.key.startsWith('/') || value.key.includes('..') || !/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(value.key)) return false
  if (typeof value.sha256 !== 'string' || !/^[0-9a-f]{64}$/i.test(value.sha256)) return false
  if (!nonNegativeInteger(value.sizeBytes) || Number(value.sizeBytes) > 100 * 1024 * 1024) return false
  if (!['application/x-ndjson', 'application/json', 'application/gpx+xml'].includes(String(value.contentType))) return false
  return validEncryption(value.encryption)
}

function validDetailRefs(value: unknown): boolean {
  if (!isRecord(value) || !hasOnlyKeys(value, DETAIL_REF_KEYS) || Object.keys(value).length < 1) return false
  return Object.values(value).every(validObjectRef)
}

function validStageResults(value: unknown): boolean {
  if (!Array.isArray(value) || value.length > 256) return false
  for (const stage of value) {
    if (!isRecord(stage) || !hasExactKeys(stage, STAGE_RESULT_KEYS)) return false
    if (!nonNegativeInteger(stage.index) || !boundedString(stage.name, 120)) return false
    if (!['DISTANCE', 'TIME'].includes(String(stage.unit))) return false
    if (!Number.isSafeInteger(stage.target) || Number(stage.target) < 1) return false
    if (!nonNegativeInteger(stage.completedAtMs) || !finiteNonNegative(stage.totalDistanceMeters)) return false
  }
  return true
}

function parseWorkout(value: unknown, entityId: string): JsonRecord | null {
  if (!isRecord(value) || !hasOnlyKeys(value, WORKOUT_KEYS) || !stringsAreSafe(value)) return null
  if ([...WORKOUT_REQUIRED_KEYS].some((key) => !(key in value))) return null
  if (value.schemaVersion !== 1 || value.id !== entityId) return null
  for (const key of [
    'startedAt', 'endedAt', 'durationMs', 'pausedDurationMs', 'steps', 'averageHeartRate',
    'planCompletedActiveMs',
  ]) {
    if (!nonNegativeInteger(value[key])) return null
  }
  if (Number(value.endedAt) < Number(value.startedAt) || Number(value.averageHeartRate) > 260) return null
  for (const key of ['distanceMeters', 'planDistanceMeters', 'freeRecordingDistanceMeters']) {
    if (!finiteNonNegative(value[key])) return null
  }
  if (!boundedString(value.planName, 200) || !boundedString(value.planGroup, 200)) return null
  if (!validStageResults(value.stageResults)) return null
  for (const key of ['averagePaceSecondsPerKm', 'averageCadenceSpm']) {
    if (value[key] !== undefined && !nonNegativeInteger(value[key])) return null
  }
  if (value.maxSmoothedSpeedMps !== undefined && !finiteNonNegative(value.maxSmoothedSpeedMps)) return null
  if (value.detailRefs !== undefined && !validDetailRefs(value.detailRefs)) return null
  return cloneWithinLimit(value)
}

function parseMutation(value: unknown): Mutation | null {
  if (!isRecord(value) || !hasExactKeys(value, MUTATION_KEYS)) return null
  if (!validUuid(value.opId) || !validEntityId(value.entityId)) return null
  if (value.entityType !== 'plan' && value.entityType !== 'workout') return null
  if (!Number.isSafeInteger(value.baseRevision) || Number(value.baseRevision) < 0) return null
  if (value.operation !== 'upsert' && value.operation !== 'delete') return null
  if (value.operation === 'delete') {
    if (value.payload !== null) return null
    return {
      opId: value.opId,
      entityType: value.entityType,
      entityId: value.entityId,
      baseRevision: Number(value.baseRevision),
      operation: 'delete',
      payload: null,
    }
  }
  const payload = value.entityType === 'plan'
    ? parsePlan(value.payload, value.entityId)
    : parseWorkout(value.payload, value.entityId)
  if (!payload) return null
  return {
    opId: value.opId,
    entityType: value.entityType,
    entityId: value.entityId,
    baseRevision: Number(value.baseRevision),
    operation: 'upsert',
    payload,
  }
}

function parseExchange(value: unknown): { deviceId: string; cursor: number; mutations: Mutation[] } | null {
  if (!isRecord(value) || !hasExactKeys(value, REQUEST_KEYS)) return null
  if (value.protocolVersion !== 1 || !validDeviceId(value.deviceId)) return null
  const cursor = decodeCursor(value.cursor)
  if (cursor === null || !Array.isArray(value.mutations) || value.mutations.length > MAX_MUTATIONS) return null
  const mutations = value.mutations.map(parseMutation)
  if (mutations.some((mutation) => mutation === null)) return null
  const parsed = mutations as Mutation[]
  if (new Set(parsed.map((mutation) => mutation.opId)).size !== parsed.length) return null
  return { deviceId: value.deviceId, cursor, mutations: parsed }
}

async function authenticateDevice(request: Request, env: SyncEnv): Promise<DeviceRow | null> {
  const token = bearerToken(request)
  const deviceId = tokenDeviceId(token)
  if (!token || !deviceId) return null
  return env.DB.prepare(
    'SELECT device_id, label, token_hash, created_at, revoked_at, last_successful_exchange_at, last_successful_push_at, last_successful_pull_at, last_cursor FROM sync_devices WHERE device_id = ? AND token_hash = ? AND revoked_at IS NULL',
  ).bind(deviceId, await sha256(token)).first<DeviceRow>()
}

function parsePayload(row: EntityRow): JsonRecord | null {
  if (Number(row.deleted) === 1 || !row.payload_json) return null
  try {
    const parsed = JSON.parse(row.payload_json)
    return isRecord(parsed) ? parsed : null
  } catch {
    return null
  }
}

function entityState(row: EntityRow): EntityState {
  return {
    entityType: row.entity_type,
    entityId: row.entity_id,
    revision: Number(row.revision),
    operation: Number(row.deleted) === 1 ? 'delete' : 'upsert',
    payload: parsePayload(row),
    deletedAt: row.deleted_at,
    updatedAt: row.updated_at,
  }
}

async function getEntity(env: SyncEnv, entityType: EntityType, entityId: string): Promise<EntityRow | null> {
  return env.DB.prepare(
    'SELECT entity_type, entity_id, revision, deleted, payload_json, created_at, updated_at, deleted_at, origin_device_id, last_operation_id FROM watch_entities WHERE entity_type = ? AND entity_id = ?',
  ).bind(entityType, entityId).first<EntityRow>()
}

async function latestSequence(env: SyncEnv): Promise<number> {
  const row = await env.DB.prepare('SELECT COALESCE(MAX(change_seq), 0) AS sequence FROM watch_changes')
    .first<{ sequence: number | string }>()
  return Number(row?.sequence ?? 0)
}

function acknowledged(mutation: Mutation, revision: number): Acknowledgement {
  return {
    outcome: 'acknowledged',
    opId: mutation.opId,
    entityType: mutation.entityType,
    entityId: mutation.entityId,
    operation: mutation.operation,
    revision,
  }
}

function conflict(
  mutation: Mutation,
  error: string,
  current: EntityRow | null,
  retryable = false,
): Conflict {
  const preserve = mutation.entityType === 'plan' && error === 'REVISION_CONFLICT'
  return {
    outcome: 'conflict',
    opId: mutation.opId,
    entityType: mutation.entityType,
    entityId: mutation.entityId,
    operation: mutation.operation,
    error,
    ...(retryable ? { retryable: true } : {}),
    current: current ? entityState(current) : null,
    candidate: mutation.payload,
    ...(preserve ? { conflictId: mutation.opId, preserveCandidate: true } : {}),
  }
}

async function reserveOperation(
  env: SyncEnv,
  deviceId: string,
  mutation: Mutation,
): Promise<
  | { kind: 'ready'; reservationId: string }
  | { kind: 'replay'; result: MutationResult }
  | { kind: 'reused' }
  | { kind: 'pending' }
> {
  const requestHash = await sha256(stableJson(mutation))
  const reservationId = crypto.randomUUID()
  const timestamp = nowIso()
  const inserted = await env.DB.prepare(
    'INSERT OR IGNORE INTO sync_operations (op_id, device_id, request_hash, reservation_id, result_json, created_at, completed_at) VALUES (?, ?, ?, ?, NULL, ?, NULL)',
  ).bind(mutation.opId, deviceId, requestHash, reservationId, timestamp).run()
  if (Number(inserted.meta.changes ?? 0) === 1) return { kind: 'ready', reservationId }

  const existing = await env.DB.prepare(
    'SELECT op_id, device_id, request_hash, reservation_id, result_json, created_at, completed_at FROM sync_operations WHERE op_id = ?',
  ).bind(mutation.opId).first<OperationRow>()
  if (!existing || existing.device_id !== deviceId || existing.request_hash !== requestHash) return { kind: 'reused' }
  if (existing.result_json) {
    try {
      const result = JSON.parse(existing.result_json) as MutationResult
      return {
        kind: 'replay',
        result: result.outcome === 'acknowledged' ? { ...result, replayed: true } : result,
      }
    } catch {
      return { kind: 'pending' }
    }
  }
  const age = Date.now() - Date.parse(existing.created_at)
  if (!Number.isFinite(age) || age < OPERATION_RESERVATION_MS) return { kind: 'pending' }
  const reclaimed = await env.DB.prepare(
    'UPDATE sync_operations SET reservation_id = ?, created_at = ? WHERE op_id = ? AND reservation_id = ? AND result_json IS NULL',
  ).bind(reservationId, timestamp, mutation.opId, existing.reservation_id).run()
  return Number(reclaimed.meta.changes ?? 0) === 1 ? { kind: 'ready', reservationId } : { kind: 'pending' }
}

async function completeOperation(
  env: SyncEnv,
  opId: string,
  reservationId: string,
  result: MutationResult,
): Promise<void> {
  await env.DB.prepare(
    'UPDATE sync_operations SET result_json = ?, completed_at = ? WHERE op_id = ? AND reservation_id = ? AND result_json IS NULL',
  ).bind(JSON.stringify(result), nowIso(), opId, reservationId).run()
}

async function completeConflict(
  env: SyncEnv,
  deviceId: string,
  mutation: Mutation,
  reservationId: string,
  result: Conflict,
): Promise<void> {
  const complete = env.DB.prepare(
    'UPDATE sync_operations SET result_json = ?, completed_at = ? WHERE op_id = ? AND reservation_id = ? AND result_json IS NULL',
  ).bind(JSON.stringify(result), nowIso(), mutation.opId, reservationId)
  if (!result.preserveCandidate) {
    await complete.run()
    return
  }
  const saveCandidate = env.DB.prepare(
    'INSERT OR IGNORE INTO plan_conflicts (conflict_id, entity_id, device_id, operation, base_revision, current_revision, candidate_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
  ).bind(
    mutation.opId,
    mutation.entityId,
    deviceId,
    mutation.operation,
    mutation.baseRevision,
    result.current?.revision ?? null,
    mutation.payload ? JSON.stringify(mutation.payload) : null,
    nowIso(),
  )
  await env.DB.batch([saveCandidate, complete])
}

async function recoverCommittedOperation(
  env: SyncEnv,
  mutation: Mutation,
  reservationId: string,
): Promise<Acknowledgement | null> {
  const row = await env.DB.prepare(
    'SELECT entity_revision, operation FROM watch_changes WHERE operation_id = ?',
  ).bind(mutation.opId).first<{ entity_revision: number | string; operation: Operation }>()
  if (!row) return null
  const result = acknowledged(mutation, Number(row.entity_revision))
  await completeOperation(env, mutation.opId, reservationId, result)
  return result
}

async function writeEntityAndChange(
  env: SyncEnv,
  deviceId: string,
  mutation: Mutation,
  current: EntityRow | null,
  reservationId: string,
): Promise<Acknowledgement | null> {
  const revision = current ? Number(current.revision) + 1 : 1
  const timestamp = nowIso()
  const deleted = mutation.operation === 'delete' ? 1 : 0
  const payloadJson = mutation.operation === 'delete' ? null : JSON.stringify(mutation.payload)
  const deletedAt = deleted ? timestamp : null
  let entityWrite: D1PreparedStatement
  if (current) {
    entityWrite = env.DB.prepare(
      'UPDATE watch_entities SET revision = ?, deleted = ?, payload_json = ?, updated_at = ?, deleted_at = ?, origin_device_id = ?, last_operation_id = ? WHERE entity_type = ? AND entity_id = ? AND revision = ?',
    ).bind(
      revision, deleted, payloadJson, timestamp, deletedAt, deviceId, mutation.opId,
      mutation.entityType, mutation.entityId, mutation.baseRevision,
    )
  } else {
    entityWrite = env.DB.prepare(
      'INSERT OR IGNORE INTO watch_entities (entity_type, entity_id, revision, deleted, payload_json, created_at, updated_at, deleted_at, origin_device_id, last_operation_id) VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?)',
    ).bind(
      mutation.entityType, mutation.entityId, deleted, payloadJson, timestamp, timestamp,
      deletedAt, deviceId, mutation.opId,
    )
  }
  const changeWrite = env.DB.prepare(
    'INSERT OR IGNORE INTO watch_changes (entity_type, entity_id, entity_revision, operation, payload_json, changed_at, origin_device_id, operation_id) SELECT entity_type, entity_id, revision, ?, payload_json, ?, origin_device_id, ? FROM watch_entities WHERE entity_type = ? AND entity_id = ? AND revision = ? AND last_operation_id = ?',
  ).bind(
    mutation.operation, timestamp, mutation.opId, mutation.entityType, mutation.entityId,
    revision, mutation.opId,
  )
  const result = acknowledged(mutation, revision)
  const complete = env.DB.prepare(
    'UPDATE sync_operations SET result_json = ?, completed_at = ? WHERE op_id = ? AND reservation_id = ? AND result_json IS NULL AND EXISTS (SELECT 1 FROM watch_changes WHERE operation_id = ?)',
  ).bind(JSON.stringify(result), timestamp, mutation.opId, reservationId, mutation.opId)
  await env.DB.batch([entityWrite, changeWrite, complete])
  const operation = await env.DB.prepare('SELECT result_json FROM sync_operations WHERE op_id = ?')
    .bind(mutation.opId).first<{ result_json: string | null }>()
  return operation?.result_json ? result : null
}

async function applyMutation(env: SyncEnv, device: DeviceRow, mutation: Mutation): Promise<MutationResult> {
  const reservation = await reserveOperation(env, device.device_id, mutation)
  if (reservation.kind === 'replay') return reservation.result
  if (reservation.kind === 'reused') {
    return conflict(mutation, 'OP_ID_REUSED', await getEntity(env, mutation.entityType, mutation.entityId))
  }
  if (reservation.kind === 'pending') {
    return conflict(
      mutation,
      'OPERATION_IN_PROGRESS',
      await getEntity(env, mutation.entityType, mutation.entityId),
      true,
    )
  }

  const recovered = await recoverCommittedOperation(env, mutation, reservation.reservationId)
  if (recovered) return recovered
  let current = await getEntity(env, mutation.entityType, mutation.entityId)
  if (mutation.entityType === 'workout' && (current !== null || mutation.operation === 'delete')) {
    const result = conflict(mutation, 'WORKOUT_IMMUTABLE', current)
    await completeConflict(env, device.device_id, mutation, reservation.reservationId, result)
    return result
  }
  if ((!current && mutation.baseRevision !== 0) || (current && Number(current.revision) !== mutation.baseRevision)) {
    const result = conflict(mutation, 'REVISION_CONFLICT', current)
    await completeConflict(env, device.device_id, mutation, reservation.reservationId, result)
    return result
  }
  const result = await writeEntityAndChange(
    env,
    device.device_id,
    mutation,
    current,
    reservation.reservationId,
  )
  if (result) return result
  current = await getEntity(env, mutation.entityType, mutation.entityId)
  const raced = (!current && mutation.baseRevision !== 0)
    || Boolean(current && Number(current.revision) !== mutation.baseRevision)
  if (!raced) throw new Error('write_not_committed')
  const racedResult = conflict(mutation, 'REVISION_CONFLICT', current)
  await completeConflict(env, device.device_id, mutation, reservation.reservationId, racedResult)
  return racedResult
}

async function readChanges(env: SyncEnv, cursor: number) {
  const page = await env.DB.prepare(
    'SELECT change_seq, entity_type, entity_id, entity_revision, operation, payload_json, changed_at, origin_device_id, operation_id FROM watch_changes WHERE change_seq > ? ORDER BY change_seq ASC LIMIT ?',
  ).bind(cursor, CHANGE_PAGE_SIZE + 1).all<{
    change_seq: number | string
    entity_type: EntityType
    entity_id: string
    entity_revision: number | string
    operation: Operation
    payload_json: string | null
    changed_at: string
    origin_device_id: string
    operation_id: string | null
  }>()
  const rows = page.results.slice(0, CHANGE_PAGE_SIZE)
  const nextSequence = rows.length ? Number(rows[rows.length - 1].change_seq) : cursor
  return {
    changes: rows.map((row) => {
      let payload: JsonRecord | null = null
      if (row.operation === 'upsert' && row.payload_json) {
        try {
          const parsed = JSON.parse(row.payload_json)
          payload = isRecord(parsed) ? parsed : null
        } catch {
          payload = null
        }
      }
      return {
        sequence: Number(row.change_seq),
        entityType: row.entity_type,
        entityId: row.entity_id,
        revision: Number(row.entity_revision),
        operation: row.operation,
        payload,
        changedAt: row.changed_at,
        originDeviceId: row.origin_device_id,
        operationId: row.operation_id,
      }
    }),
    nextCursor: encodeCursor(nextSequence),
    hasMore: page.results.length > CHANGE_PAGE_SIZE,
  }
}

async function handleExchange(request: Request, env: SyncEnv): Promise<Response> {
  const device = await authenticateDevice(request, env)
  if (!device) return errorJson('unauthorized', 401)
  let raw: unknown
  try {
    raw = await readJson(request, MAX_EXCHANGE_BYTES)
  } catch (caught) {
    const code = caught instanceof Error ? caught.message : 'invalid_json'
    return errorJson(code, code === 'payload_too_large' ? 413 : code === 'unsupported_media_type' ? 415 : 400)
  }
  const exchange = parseExchange(raw)
  if (!exchange) return errorJson('invalid_exchange', 400)
  if (exchange.deviceId !== device.device_id) return errorJson('device_mismatch', 403)
  const latest = await latestSequence(env)
  if (exchange.cursor > latest) {
    return errorJson('cursor_ahead', 409, { latestCursor: encodeCursor(latest), resetCursor: null })
  }

  const results: MutationResult[] = []
  try {
    for (const mutation of exchange.mutations) results.push(await applyMutation(env, device, mutation))
  } catch {
    return errorJson('sync_temporarily_unavailable', 503, { retryable: true })
  }
  const acknowledgedResults = results.filter(
    (result): result is Acknowledgement => result.outcome === 'acknowledged',
  )
  const conflictResults = results.filter((result): result is Conflict => result.outcome === 'conflict')
  const feed = await readChanges(env, exchange.cursor)
  const timestamp = nowIso()
  await env.DB.prepare(
    'UPDATE sync_devices SET last_successful_exchange_at = ?, last_successful_push_at = CASE WHEN ? = 1 THEN ? ELSE last_successful_push_at END, last_successful_pull_at = ?, last_cursor = ? WHERE device_id = ? AND revoked_at IS NULL',
  ).bind(
    timestamp,
    acknowledgedResults.length > 0 ? 1 : 0,
    timestamp,
    timestamp,
    feed.nextCursor,
    device.device_id,
  ).run()
  return json({
    protocolVersion: 1,
    authority: 'remote_authoritative',
    acknowledged: acknowledgedResults,
    conflicts: conflictResults,
    changes: feed.changes,
    nextCursor: feed.nextCursor,
    hasMore: feed.hasMore,
    serverTime: timestamp,
  })
}

async function handleEncryptedExchange(request: Request, env: SyncEnv): Promise<Response> {
  const device = await authenticateDevice(request, env)
  if (!device) return errorJson('unauthorized', 401)
  let raw: unknown
  try {
    raw = await readJson(request, MAX_EXCHANGE_BYTES)
  } catch (caught) {
    const code = caught instanceof Error ? caught.message : 'invalid_json'
    return errorJson(code, code === 'payload_too_large' ? 413 : code === 'unsupported_media_type' ? 415 : 400)
  }
  let projection
  let encryptedRaw = raw
  if (isRecord(raw) && Object.hasOwn(raw, 'readProjection')) {
    projection = parseReadProjection(raw.readProjection)
    if (!projection) return errorJson('invalid_read_projection', 400)
    const coreEnvelope = { ...raw }
    delete coreEnvelope.readProjection
    encryptedRaw = coreEnvelope
  }
  const exchange = await parseEncryptedExchange(
    encryptedRaw, ENCRYPTED_SYNC_PRODUCT, isValidWatchEncryptedEntity,
  )
  if (!exchange) return errorJson('invalid_encrypted_exchange', 400)
  if (exchange.deviceId !== device.device_id) return errorJson('device_mismatch', 403)
  try {
    const result = await exchangeEncrypted(
      env.DB,
      ENCRYPTED_SYNC_PRODUCT,
      device.device_id,
      exchange,
      { immutableEntityTypes: ['workout'] },
    )
    if ('error' in result) return json(result, 409)
    if (projection) {
      await replaceReadProjection(
        env.DB, ENCRYPTED_SYNC_PRODUCT, device.device_id, projection, result.serverTime,
        encodeCursor(exchange.cursor),
      )
    }
    return json(result)
  } catch {
    return errorJson('sync_temporarily_unavailable', 503, { retryable: true })
  }
}

async function handleStatus(request: Request, env: SyncEnv): Promise<Response> {
  const device = await authenticateDevice(request, env)
  if (!device) return errorJson('unauthorized', 401)
  const claimedDeviceId = request.headers.get('x-watch-device-id')
  if (!claimedDeviceId || claimedDeviceId !== device.device_id) {
    return errorJson('device_mismatch', 403)
  }
  const latest = await latestSequence(env)
  const deviceCursor = decodeCursor(device.last_cursor) ?? 0
  const conflicts = await env.DB.prepare('SELECT COUNT(*) AS count FROM plan_conflicts WHERE device_id = ?')
    .bind(device.device_id).first<{ count: number | string }>()
  return json({
    protocolVersion: 1,
    authority: 'remote_authoritative',
    deviceId: device.device_id,
    deviceState: 'active',
    localCatchup: {
      deviceCursor: device.last_cursor,
      latestCursor: encodeCursor(latest),
      pendingRemoteChanges: Math.max(0, latest - deviceCursor),
    },
    unresolvedPlanConflicts: Number(conflicts?.count ?? 0),
    lastSuccessfulExchangeAt: device.last_successful_exchange_at,
    lastSuccessfulPushAt: device.last_successful_push_at,
    lastSuccessfulPullAt: device.last_successful_pull_at,
  })
}

async function handleEncryptedStatus(request: Request, env: SyncEnv): Promise<Response> {
  const device = await authenticateDevice(request, env)
  if (!device) return errorJson('unauthorized', 401)
  const claimedDeviceId = request.headers.get('x-watch-device-id')
  if (!claimedDeviceId || claimedDeviceId !== device.device_id) {
    return errorJson('device_mismatch', 403)
  }
  try {
    return json(await encryptedStatus(env.DB, ENCRYPTED_SYNC_PRODUCT, device.device_id))
  } catch {
    return errorJson('sync_temporarily_unavailable', 503, { retryable: true })
  }
}

function isAdmin(request: Request, env: SyncEnv): boolean {
  return Boolean(env.SYNC_KEY) && bearerToken(request) === env.SYNC_KEY
}

async function adminBody(request: Request): Promise<JsonRecord> {
  const raw = await readJson(request, MAX_ADMIN_BYTES)
  if (!isRecord(raw)) throw new Error('invalid_device')
  return raw
}

function validLabel(value: unknown): value is string {
  return boundedString(value, 80, false)
}

async function handleProvision(request: Request, env: SyncEnv): Promise<Response> {
  if (!env.SYNC_KEY) return errorJson('sync_admin_not_configured', 503)
  if (!isAdmin(request, env)) return errorJson('unauthorized', 401)
  let body: JsonRecord
  try {
    body = await adminBody(request)
  } catch (caught) {
    const code = caught instanceof Error ? caught.message : 'invalid_json'
    return errorJson(code, code === 'payload_too_large' ? 413 : code === 'unsupported_media_type' ? 415 : 400)
  }
  if (!hasExactKeys(body, new Set(['label'])) || !validLabel(body.label)) {
    return errorJson('invalid_device', 400)
  }
  const deviceId = `watch-${crypto.randomUUID()}`
  const token = `${DEVICE_TOKEN_PREFIX}.${deviceId}.${randomBase64Url()}`
  const timestamp = nowIso()
  await env.DB.prepare(
    'INSERT INTO sync_devices (device_id, label, token_hash, created_at, revoked_at, last_successful_exchange_at, last_successful_push_at, last_successful_pull_at, last_cursor) VALUES (?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL)',
  ).bind(deviceId, body.label, await sha256(token), timestamp).run()
  return json({
    deviceId,
    credentialType: 'bearer',
    tokenType: 'Bearer',
    deviceToken: token,
    issuedAt: timestamp,
  }, 201)
}

async function handleRotate(request: Request, env: SyncEnv, deviceId: string): Promise<Response> {
  if (!env.SYNC_KEY) return errorJson('sync_admin_not_configured', 503)
  if (!isAdmin(request, env)) return errorJson('unauthorized', 401)
  if (!validDeviceId(deviceId)) return errorJson('invalid_device', 400)
  let body: JsonRecord
  try {
    body = await adminBody(request)
  } catch (caught) {
    const code = caught instanceof Error ? caught.message : 'invalid_json'
    return errorJson(code, code === 'payload_too_large' ? 413 : code === 'unsupported_media_type' ? 415 : 400)
  }
  if (!hasExactKeys(body, new Set(['label'])) || !validLabel(body.label)) {
    return errorJson('invalid_device', 400)
  }
  const token = `${DEVICE_TOKEN_PREFIX}.${deviceId}.${randomBase64Url()}`
  const result = await env.DB.prepare(
    'UPDATE sync_devices SET label = ?, token_hash = ?, revoked_at = NULL WHERE device_id = ?',
  ).bind(body.label, await sha256(token), deviceId).run()
  if (Number(result.meta.changes ?? 0) < 1) return errorJson('device_not_found', 404)
  return json({
    deviceId,
    credentialType: 'bearer',
    tokenType: 'Bearer',
    deviceToken: token,
    issuedAt: nowIso(),
  })
}

async function handleRevoke(request: Request, env: SyncEnv, deviceId: string): Promise<Response> {
  if (!env.SYNC_KEY) return errorJson('sync_admin_not_configured', 503)
  if (!isAdmin(request, env)) return errorJson('unauthorized', 401)
  if (!validDeviceId(deviceId)) return errorJson('invalid_device', 400)
  const timestamp = nowIso()
  const result = await env.DB.prepare(
    'UPDATE sync_devices SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL',
  ).bind(timestamp, deviceId).run()
  // The authority checkpoint trigger adds its own D1 row change on a successful revoke.
  if (Number(result.meta.changes ?? 0) < 1) return errorJson('device_not_found', 404)
  return json({ deviceId, revokedAt: timestamp })
}

function decodeDevicePath(pathname: string, suffix = ''): string | null {
  const prefix = '/sync/v1/devices/'
  if (!pathname.startsWith(prefix) || (suffix && !pathname.endsWith(suffix))) return null
  const encoded = pathname.slice(prefix.length, suffix ? -suffix.length : undefined)
  if (!encoded || encoded.includes('/')) return null
  try {
    return decodeURIComponent(encoded)
  } catch {
    return null
  }
}

export async function routeSync(request: Request, env: SyncEnv): Promise<Response | null> {
  const url = new URL(request.url)
  if (url.pathname.startsWith('/sync/') && request.method === 'OPTIONS') {
    return new Response(null, {
      status: 204,
      headers: {
        'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
        'Access-Control-Allow-Headers': 'Authorization, Content-Type',
        'Access-Control-Max-Age': '600',
      },
    })
  }
  if (url.pathname === '/sync/push' || url.pathname.startsWith('/sync/push/')) {
    return errorJson('snapshot_sync_deprecated', 410, { replacement: '/sync/v2/exchange' })
  }
  if (url.pathname === '/sync/v1/exchange' || url.pathname === '/sync/v1/status') {
    if (env.ALLOW_LEGACY_SYNC_V1 !== 'contract-test-only') {
      return errorJson('plaintext_sync_deprecated', 410, { replacement: url.pathname.endsWith('/status')
        ? '/sync/v2/status'
        : '/sync/v2/exchange' })
    }
  }
  if (url.pathname === '/sync/v1/exchange' && request.method === 'POST') return handleExchange(request, env)
  if (url.pathname === '/sync/v1/status' && request.method === 'GET') return handleStatus(request, env)
  if (url.pathname === '/sync/v2/exchange' && request.method === 'POST') return handleEncryptedExchange(request, env)
  if (url.pathname === '/sync/v2/status' && request.method === 'GET') return handleEncryptedStatus(request, env)
  if (url.pathname === '/sync/v1/devices' && request.method === 'POST') return handleProvision(request, env)
  if (request.method === 'POST' && url.pathname.endsWith('/rotate')) {
    const deviceId = decodeDevicePath(url.pathname, '/rotate')
    return deviceId ? handleRotate(request, env, deviceId) : errorJson('invalid_device', 400)
  }
  if (request.method === 'DELETE') {
    const deviceId = decodeDevicePath(url.pathname)
    if (deviceId) return handleRevoke(request, env, deviceId)
  }
  return null
}

export async function listLiveEntities(
  db: D1Database,
  entityType: EntityType,
  limit = 200,
): Promise<EntityState[]> {
  const result = await db.prepare(
    'SELECT entity_type, entity_id, revision, deleted, payload_json, created_at, updated_at, deleted_at, origin_device_id, last_operation_id FROM watch_entities WHERE entity_type = ? AND deleted = 0 ORDER BY updated_at DESC, entity_id LIMIT ?',
  ).bind(entityType, Math.max(1, Math.min(500, Math.trunc(limit)))).all<EntityRow>()
  return result.results.map(entityState)
}

export async function syncOverview(db: D1Database) {
  const [entities, devices, latest] = await Promise.all([
    db.prepare(
      'SELECT entity_type, COUNT(*) AS count, SUM(deleted) AS tombstones, MAX(updated_at) AS updated_at FROM watch_entities GROUP BY entity_type ORDER BY entity_type',
    ).all<JsonRecord>(),
    db.prepare(
      'SELECT device_id, label, created_at, revoked_at, last_successful_exchange_at, last_successful_push_at, last_successful_pull_at, last_cursor FROM sync_devices ORDER BY device_id',
    ).all<JsonRecord>(),
    db.prepare('SELECT COALESCE(MAX(change_seq), 0) AS sequence FROM watch_changes')
      .first<{ sequence: number | string }>(),
  ])
  return {
    authority: 'remote_authoritative',
    latestCursor: encodeCursor(Number(latest?.sequence ?? 0)),
    entities: entities.results,
    devices: devices.results,
  }
}
