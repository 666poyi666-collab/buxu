/**
 * SyncEnvelopeV1 data plane for Watch.
 *
 * The Worker validates identity and envelope integrity but never receives a
 * business plaintext or a root encryption key. AES-256-GCM encryption and
 * decryption happen on authorized devices; D1 stores only ciphertext and the
 * minimum metadata needed for idempotency, ordering, and tombstones.
 */

type JsonRecord = Record<string, unknown>

export type EncryptedObjectRef = {
  objectKey: string
  ciphertextSha256: string
  ciphertextBytes: number
  nonce: string
  aadHash: string
  keyVersion: number
}

export type EncryptedMutation = {
  opId: string
  entityType: string
  entityId: string
  baseRevision: number
  operation: 'upsert' | 'delete'
  keyVersion: number
  ciphertext: string | null
  nonce: string | null
  aadHash: string
  objects: EncryptedObjectRef[]
}

export type EncryptedExchange = {
  deviceId: string
  cursor: number
  mutations: EncryptedMutation[]
}

type EncryptedEntityRow = {
  entity_type: string
  entity_id: string
  revision: number | string
  deleted: number | string
  ciphertext: string | null
  nonce: string | null
  aad_hash: string
  key_version: number | string
  objects_json: string
  created_at: string
  updated_at: string
  deleted_at: string | null
  origin_device_id: string
  last_operation_id: string
}

type EncryptedOperationRow = {
  device_id: string
  request_hash: string
  result_json: string | null
}

export type EncryptedState = {
  entityType: string
  entityId: string
  revision: number
  operation: 'upsert' | 'delete'
  keyVersion: number
  ciphertext: string | null
  nonce: string | null
  aadHash: string
  objects: EncryptedObjectRef[]
  deletedAt: string | null
  updatedAt: string
}

export type EncryptedAcknowledgement = {
  outcome: 'acknowledged'
  opId: string
  entityType: string
  entityId: string
  operation: 'upsert' | 'delete'
  revision: number
  replayed?: boolean
}

export type EncryptedConflict = {
  outcome: 'conflict'
  opId: string
  entityType: string
  entityId: string
  operation: 'upsert' | 'delete'
  error: string
  retryable?: boolean
  current: EncryptedState | null
  candidate: EncryptedMutation | null
}

export type EncryptedMutationResult = EncryptedAcknowledgement | EncryptedConflict

export type EncryptedChange = {
  entityType: string
  entityId: string
  revision: number
  operation: 'upsert' | 'delete'
  keyVersion: number
  ciphertext: string | null
  nonce: string | null
  aadHash: string
  objects: EncryptedObjectRef[]
  changedAt: string
  originDeviceId: string
  operationId: string
}

export type EncryptedExchangeResponse = {
  protocolVersion: 2
  envelopeVersion: 1
  product: string
  acknowledged: EncryptedAcknowledgement[]
  conflicts: EncryptedConflict[]
  changes: EncryptedChange[]
  nextCursor: string
  hasMore: boolean
  serverTime: string
}

export type EntityValidator = (entityType: string, entityId: string) => boolean

const MAX_MUTATIONS = 25
const MAX_CIPHERTEXT_CHARS = 900_000
const CHANGE_PAGE_SIZE = 100

function isRecord(value: unknown): value is JsonRecord {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function hasExactKeys(value: JsonRecord, expected: readonly string[]): boolean {
  const keys = Object.keys(value)
  return keys.length === expected.length && keys.every((key) => expected.includes(key))
}

function isDeviceId(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{2,127}$/.test(value)
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
}

function isCursor(value: unknown): number | null {
  if (value === null) return 0
  if (typeof value !== 'string' || !/^c[0-9a-z]+$/.test(value)) return null
  const parsed = Number.parseInt(value.slice(1), 36)
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null
}

function isBase64Url(value: unknown, maximum = MAX_CIPHERTEXT_CHARS): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= maximum && /^[A-Za-z0-9_-]+$/.test(value)
}

function isSha256(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
}

function isNonce(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{16}$/.test(value)
}

function isTimestamp(value: unknown): value is string {
  return typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
    Number.isFinite(Date.parse(value))
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

function nowIso(): string {
  return new Date().toISOString()
}

function encodeCursor(sequence: number): string {
  return `c${Math.max(0, Math.trunc(sequence)).toString(36)}`
}

function mutationAad(
  product: string,
  mutation: Pick<EncryptedMutation, 'entityType' | 'entityId' | 'baseRevision' | 'operation' | 'keyVersion'>,
): JsonRecord {
  return {
    envelopeVersion: 1,
    entityId: mutation.entityId,
    entityType: mutation.entityType,
    keyVersion: mutation.keyVersion,
    operation: mutation.operation,
    product,
    revision: mutation.baseRevision + 1,
  }
}

function objectAad(
  product: string,
  mutation: Pick<EncryptedMutation, 'entityType' | 'entityId' | 'baseRevision' | 'operation' | 'keyVersion'>,
  objectKey: string,
): JsonRecord {
  return { ...mutationAad(product, mutation), objectKey }
}

async function parseObject(
  value: unknown,
  product: string,
  mutation: Pick<EncryptedMutation, 'entityType' | 'entityId' | 'baseRevision' | 'operation' | 'keyVersion'>,
): Promise<EncryptedObjectRef | null> {
  if (!isRecord(value) || !hasExactKeys(value, ['objectKey', 'ciphertextSha256', 'ciphertextBytes', 'nonce', 'aadHash', 'keyVersion'])) return null
  if (
    typeof value.objectKey !== 'string' ||
    !/^[A-Za-z0-9][A-Za-z0-9._/-]{0,511}$/.test(value.objectKey) ||
    value.objectKey.includes('..') ||
    !isSha256(value.ciphertextSha256) ||
    !Number.isSafeInteger(value.ciphertextBytes) ||
    Number(value.ciphertextBytes) < 1 ||
    Number(value.ciphertextBytes) > 1_073_741_824 ||
    !isNonce(value.nonce) ||
    !isSha256(value.aadHash) ||
    value.keyVersion !== mutation.keyVersion
  ) return null
  const expectedAadHash = await sha256(stableJson(objectAad(product, mutation, value.objectKey)))
  if (value.aadHash !== expectedAadHash) return null
  return {
    objectKey: value.objectKey,
    ciphertextSha256: value.ciphertextSha256,
    ciphertextBytes: Number(value.ciphertextBytes),
    nonce: value.nonce,
    aadHash: value.aadHash,
    keyVersion: mutation.keyVersion,
  }
}

async function parseMutation(
  value: unknown,
  product: string,
  validateEntity: EntityValidator,
): Promise<EncryptedMutation | null> {
  if (!isRecord(value) || !hasExactKeys(value, [
    'opId', 'entityType', 'entityId', 'baseRevision', 'operation', 'keyVersion', 'ciphertext', 'nonce', 'aadHash', 'objects',
  ])) return null
  if (
    !isUuid(value.opId) ||
    typeof value.entityType !== 'string' ||
    !/^[a-z][a-z0-9_:-]{0,63}$/.test(value.entityType) ||
    typeof value.entityId !== 'string' ||
    !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,191}$/.test(value.entityId) ||
    !validateEntity(value.entityType, value.entityId) ||
    !Number.isSafeInteger(value.baseRevision) ||
    Number(value.baseRevision) < 0 ||
    (value.operation !== 'upsert' && value.operation !== 'delete') ||
    !Number.isSafeInteger(value.keyVersion) ||
    Number(value.keyVersion) < 1 ||
    Number(value.keyVersion) > 2_147_483_647 ||
    !isSha256(value.aadHash) ||
    !Array.isArray(value.objects) ||
    value.objects.length > 100
  ) return null
  const mutation: EncryptedMutation = {
    opId: value.opId,
    entityType: value.entityType,
    entityId: value.entityId,
    baseRevision: Number(value.baseRevision),
    operation: value.operation,
    keyVersion: Number(value.keyVersion),
    ciphertext: null,
    nonce: null,
    aadHash: value.aadHash,
    objects: [],
  }
  const expectedAadHash = await sha256(stableJson(mutationAad(product, mutation)))
  if (mutation.aadHash !== expectedAadHash) return null
  if (mutation.operation === 'upsert') {
    if (!isBase64Url(value.ciphertext) || !isNonce(value.nonce)) return null
    mutation.ciphertext = value.ciphertext
    mutation.nonce = value.nonce
    const objects = await Promise.all(value.objects.map((object) => parseObject(object, product, mutation)))
    if (objects.some((object) => object === null)) return null
    mutation.objects = objects as EncryptedObjectRef[]
    if (new Set(mutation.objects.map((object) => object.objectKey)).size !== mutation.objects.length) return null
    return mutation
  }
  if (value.ciphertext !== null || value.nonce !== null || value.objects.length !== 0) return null
  return mutation
}

export async function parseEncryptedExchange(
  value: unknown,
  product: string,
  validateEntity: EntityValidator,
): Promise<EncryptedExchange | null> {
  if (!isRecord(value) || !hasExactKeys(value, ['protocolVersion', 'envelopeVersion', 'product', 'deviceId', 'cursor', 'mutations'])) return null
  if (
    value.protocolVersion !== 2 ||
    value.envelopeVersion !== 1 ||
    value.product !== product ||
    !isDeviceId(value.deviceId) ||
    !Array.isArray(value.mutations) ||
    value.mutations.length > MAX_MUTATIONS
  ) return null
  const cursor = isCursor(value.cursor)
  if (cursor === null) return null
  const mutations = await Promise.all(value.mutations.map((mutation) => parseMutation(mutation, product, validateEntity)))
  if (mutations.some((mutation) => mutation === null)) return null
  const parsed = mutations as EncryptedMutation[]
  if (new Set(parsed.map((mutation) => mutation.opId)).size !== parsed.length) return null
  return { deviceId: value.deviceId, cursor, mutations: parsed }
}

function parseObjects(value: string): EncryptedObjectRef[] {
  try {
    const parsed = JSON.parse(value)
    if (!Array.isArray(parsed)) return []
    return parsed as EncryptedObjectRef[]
  } catch {
    return []
  }
}

function stateFromRow(row: EncryptedEntityRow): EncryptedState {
  return {
    entityType: row.entity_type,
    entityId: row.entity_id,
    revision: Number(row.revision),
    operation: Number(row.deleted) === 1 ? 'delete' : 'upsert',
    keyVersion: Number(row.key_version),
    ciphertext: Number(row.deleted) === 1 ? null : row.ciphertext,
    nonce: Number(row.deleted) === 1 ? null : row.nonce,
    aadHash: row.aad_hash,
    objects: Number(row.deleted) === 1 ? [] : parseObjects(row.objects_json),
    deletedAt: row.deleted_at,
    updatedAt: row.updated_at,
  }
}

function acknowledged(mutation: EncryptedMutation, revision: number): EncryptedAcknowledgement {
  return {
    outcome: 'acknowledged',
    opId: mutation.opId,
    entityType: mutation.entityType,
    entityId: mutation.entityId,
    operation: mutation.operation,
    revision,
  }
}

function operationInProgress(mutation: EncryptedMutation): EncryptedConflict {
  return {
    outcome: 'conflict',
    opId: mutation.opId,
    entityType: mutation.entityType,
    entityId: mutation.entityId,
    operation: mutation.operation,
    error: 'OPERATION_IN_PROGRESS',
    retryable: true,
    current: null,
    candidate: mutation,
  }
}

function operationReused(mutation: EncryptedMutation): EncryptedConflict {
  return {
    outcome: 'conflict',
    opId: mutation.opId,
    entityType: mutation.entityType,
    entityId: mutation.entityId,
    operation: mutation.operation,
    error: 'OP_ID_REUSED',
    current: null,
    candidate: mutation,
  }
}

function entityWriteStatement(
  db: D1Database,
  product: string,
  deviceId: string,
  mutation: EncryptedMutation,
  reservationId: string,
  timestamp: string,
  immutableExisting = false,
): D1PreparedStatement {
  const deleted = mutation.operation === 'delete' ? 1 : 0
  const targetRevision = mutation.baseRevision + 1
  const ciphertext = deleted ? null : mutation.ciphertext
  const nonce = deleted ? null : mutation.nonce
  const objectsJson = JSON.stringify(deleted ? [] : mutation.objects)
  const deletedAt = deleted ? timestamp : null
  return db.prepare(`
    INSERT INTO encrypted_sync_entities (
      product, entity_type, entity_id, revision, deleted, ciphertext, nonce,
      aad_hash, key_version, objects_json, created_at, updated_at, deleted_at,
      origin_device_id, last_operation_id
    )
    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
    WHERE EXISTS (
      SELECT 1 FROM encrypted_sync_operations
      WHERE product = ? AND op_id = ? AND reservation_id = ? AND result_json IS NULL
    )
    ON CONFLICT(product, entity_type, entity_id) DO UPDATE SET
      revision = excluded.revision,
      deleted = excluded.deleted,
      ciphertext = excluded.ciphertext,
      nonce = excluded.nonce,
      aad_hash = excluded.aad_hash,
      key_version = excluded.key_version,
      objects_json = excluded.objects_json,
      updated_at = excluded.updated_at,
      deleted_at = excluded.deleted_at,
      origin_device_id = excluded.origin_device_id,
      last_operation_id = excluded.last_operation_id
    WHERE encrypted_sync_entities.revision = ?
      AND ? = 0
      AND EXISTS (
        SELECT 1 FROM encrypted_sync_operations
        WHERE product = ? AND op_id = ? AND reservation_id = ? AND result_json IS NULL
      )
  `).bind(
    product,
    mutation.entityType,
    mutation.entityId,
    targetRevision,
    deleted,
    ciphertext,
    nonce,
    mutation.aadHash,
    mutation.keyVersion,
    objectsJson,
    timestamp,
    timestamp,
    deletedAt,
    deviceId,
    mutation.opId,
    product,
    mutation.opId,
    reservationId,
    mutation.baseRevision,
    immutableExisting ? 1 : 0,
    product,
    mutation.opId,
    reservationId,
  )
}

function changeWriteStatement(
  db: D1Database,
  product: string,
  deviceId: string,
  mutation: EncryptedMutation,
  reservationId: string,
  timestamp: string,
): D1PreparedStatement {
  return db.prepare(`
    INSERT INTO encrypted_sync_changes (
      product, entity_type, entity_id, entity_revision, operation, ciphertext,
      nonce, aad_hash, key_version, objects_json, changed_at, origin_device_id, operation_id
    )
    SELECT ?, e.entity_type, e.entity_id, e.revision, ?, e.ciphertext,
      e.nonce, e.aad_hash, e.key_version, e.objects_json, ?, ?, ?
    FROM encrypted_sync_entities e
    WHERE e.product = ? AND e.entity_type = ? AND e.entity_id = ?
      AND e.last_operation_id = ?
      AND EXISTS (
        SELECT 1 FROM encrypted_sync_operations
        WHERE product = ? AND op_id = ? AND reservation_id = ? AND result_json IS NULL
      )
  `).bind(
    product,
    mutation.operation,
    timestamp,
    deviceId,
    mutation.opId,
    product,
    mutation.entityType,
    mutation.entityId,
    mutation.opId,
    product,
    mutation.opId,
    reservationId,
  )
}

function completeOperationStatement(
  db: D1Database,
  product: string,
  mutation: EncryptedMutation,
  reservationId: string,
  timestamp: string,
  conflictError = 'REVISION_CONFLICT',
): D1PreparedStatement {
  const success = JSON.stringify(acknowledged(mutation, mutation.baseRevision + 1))
  const candidate = JSON.stringify(mutation)
  return db.prepare(`
    UPDATE encrypted_sync_operations
    SET result_json = CASE
      WHEN EXISTS (
        SELECT 1 FROM encrypted_sync_entities
        WHERE product = ? AND entity_type = ? AND entity_id = ? AND last_operation_id = ?
      ) THEN ?
      ELSE json_object(
        'outcome', 'conflict',
        'opId', ?,
        'entityType', ?,
        'entityId', ?,
        'operation', ?,
        'error', ?,
        'current', (
          SELECT json_object(
            'entityType', entity_type,
            'entityId', entity_id,
            'revision', revision,
            'operation', CASE WHEN deleted = 1 THEN 'delete' ELSE 'upsert' END,
            'keyVersion', key_version,
            'ciphertext', CASE WHEN deleted = 1 THEN NULL ELSE ciphertext END,
            'nonce', CASE WHEN deleted = 1 THEN NULL ELSE nonce END,
            'aadHash', aad_hash,
            'objects', CASE WHEN deleted = 1 THEN json('[]') ELSE json(objects_json) END,
            'deletedAt', deleted_at,
            'updatedAt', updated_at
          )
          FROM encrypted_sync_entities
          WHERE product = ? AND entity_type = ? AND entity_id = ?
        ),
        'candidate', json(?)
      )
    END,
    completed_at = ?
    WHERE product = ? AND op_id = ? AND reservation_id = ? AND result_json IS NULL
  `).bind(
    product,
    mutation.entityType,
    mutation.entityId,
    mutation.opId,
    success,
    mutation.opId,
    mutation.entityType,
    mutation.entityId,
    mutation.operation,
    conflictError,
    product,
    mutation.entityType,
    mutation.entityId,
    candidate,
    timestamp,
    product,
    mutation.opId,
    reservationId,
  )
}

async function applyMutation(
  db: D1Database,
  product: string,
  deviceId: string,
  mutation: EncryptedMutation,
  immutableExisting: boolean,
): Promise<EncryptedMutationResult> {
  const requestHash = await sha256(stableJson(mutation))
  const reservationId = crypto.randomUUID()
  const timestamp = nowIso()
  const responses = await db.batch([
    db.prepare(
      `INSERT OR IGNORE INTO encrypted_sync_operations (
        product, op_id, device_id, request_hash, reservation_id, result_json, created_at, completed_at
      ) VALUES (?, ?, ?, ?, ?, NULL, ?, NULL)`,
    ).bind(product, mutation.opId, deviceId, requestHash, reservationId, timestamp),
    entityWriteStatement(db, product, deviceId, mutation, reservationId, timestamp, immutableExisting),
    changeWriteStatement(db, product, deviceId, mutation, reservationId, timestamp),
    completeOperationStatement(
      db,
      product,
      mutation,
      reservationId,
      timestamp,
      immutableExisting ? 'IMMUTABLE_ENTITY' : 'REVISION_CONFLICT',
    ),
  ])
  // D1 may include authority-checkpoint trigger writes in meta.changes. A zero still means the
  // INSERT OR IGNORE lost; any positive count means this reservation was created by this call.
  const created = Number(responses[0].meta.changes ?? 0) > 0
  const row = await db.prepare(
    'SELECT device_id, request_hash, result_json FROM encrypted_sync_operations WHERE product = ? AND op_id = ?',
  ).bind(product, mutation.opId).first<EncryptedOperationRow>()
  if (!row) throw new Error('encrypted_operation_missing')
  if (row.device_id !== deviceId || row.request_hash !== requestHash) return operationReused(mutation)
  if (!row.result_json) return operationInProgress(mutation)
  let result: EncryptedMutationResult
  try {
    result = JSON.parse(row.result_json) as EncryptedMutationResult
  } catch {
    throw new Error('encrypted_operation_result_invalid')
  }
  return !created && result.outcome === 'acknowledged' ? { ...result, replayed: true } : result
}

async function latestSequence(db: D1Database, product: string): Promise<number> {
  const row = await db.prepare(
    'SELECT COALESCE(MAX(change_seq), 0) AS sequence FROM encrypted_sync_changes WHERE product = ?',
  ).bind(product).first<{ sequence: number | string }>()
  return Number(row?.sequence ?? 0)
}

async function readChanges(
  db: D1Database,
  product: string,
  cursor: number,
): Promise<Pick<EncryptedExchangeResponse, 'changes' | 'nextCursor' | 'hasMore'>> {
  const query = await db.prepare(`
    SELECT change_seq, entity_type, entity_id, entity_revision, operation, ciphertext, nonce,
      aad_hash, key_version, objects_json, changed_at, origin_device_id, operation_id
    FROM encrypted_sync_changes
    WHERE product = ? AND change_seq > ?
    ORDER BY change_seq ASC
    LIMIT ?
  `).bind(product, cursor, CHANGE_PAGE_SIZE + 1).all<{
    change_seq: number | string
    entity_type: string
    entity_id: string
    entity_revision: number | string
    operation: 'upsert' | 'delete'
    ciphertext: string | null
    nonce: string | null
    aad_hash: string
    key_version: number | string
    objects_json: string
    changed_at: string
    origin_device_id: string
    operation_id: string
  }>()
  const rows = query.results.slice(0, CHANGE_PAGE_SIZE)
  const next = rows.length ? Number(rows[rows.length - 1].change_seq) : cursor
  return {
    changes: rows.map((row) => ({
      entityType: row.entity_type,
      entityId: row.entity_id,
      revision: Number(row.entity_revision),
      operation: row.operation,
      keyVersion: Number(row.key_version),
      ciphertext: row.operation === 'delete' ? null : row.ciphertext,
      nonce: row.operation === 'delete' ? null : row.nonce,
      aadHash: row.aad_hash,
      objects: row.operation === 'delete' ? [] : parseObjects(row.objects_json),
      changedAt: row.changed_at,
      originDeviceId: row.origin_device_id,
      operationId: row.operation_id,
    })),
    nextCursor: encodeCursor(next),
    hasMore: query.results.length > CHANGE_PAGE_SIZE,
  }
}

export async function exchangeEncrypted(
  db: D1Database,
  product: string,
  deviceId: string,
  request: EncryptedExchange,
  options: { immutableEntityTypes?: readonly string[] } = {},
): Promise<EncryptedExchangeResponse | { error: 'cursor_ahead'; latestCursor: string; resetCursor: null }> {
  const latest = await latestSequence(db, product)
  if (request.cursor > latest) {
    return { error: 'cursor_ahead', latestCursor: encodeCursor(latest), resetCursor: null }
  }
  const results: EncryptedMutationResult[] = []
  for (const mutation of request.mutations) {
    results.push(await applyMutation(
      db,
      product,
      deviceId,
      mutation,
      options.immutableEntityTypes?.includes(mutation.entityType) === true,
    ))
  }
  const feed = await readChanges(db, product, request.cursor)
  const timestamp = nowIso()
  const acknowledged = results.filter((result): result is EncryptedAcknowledgement => result.outcome === 'acknowledged')
  const conflicts = results.filter((result): result is EncryptedConflict => result.outcome === 'conflict')
  await db.batch([
    db.prepare(`
      INSERT INTO encrypted_sync_device_state (
        product, device_id, last_cursor, last_successful_exchange_at,
        last_successful_push_at, last_successful_pull_at
      ) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(product, device_id) DO UPDATE SET
        last_cursor = excluded.last_cursor,
        last_successful_exchange_at = excluded.last_successful_exchange_at,
        last_successful_push_at = CASE WHEN ? = 1 THEN excluded.last_successful_push_at ELSE encrypted_sync_device_state.last_successful_push_at END,
        last_successful_pull_at = excluded.last_successful_pull_at
    `).bind(
      product,
      deviceId,
      feed.nextCursor,
      timestamp,
      timestamp,
      timestamp,
      acknowledged.length > 0 ? 1 : 0,
    ),
    db.prepare(`
      INSERT INTO encrypted_sync_audit (
        product, event_at, device_id, mutation_count, acknowledged_count, conflict_count
      ) VALUES (?, ?, ?, ?, ?, ?)
    `).bind(product, timestamp, deviceId, request.mutations.length, acknowledged.length, conflicts.length),
  ])
  return {
    protocolVersion: 2,
    envelopeVersion: 1,
    product,
    acknowledged,
    conflicts,
    changes: feed.changes,
    nextCursor: feed.nextCursor,
    hasMore: feed.hasMore,
    serverTime: timestamp,
  }
}

export async function encryptedStatus(
  db: D1Database,
  product: string,
  deviceId: string,
): Promise<JsonRecord> {
  const [latest, state] = await Promise.all([
    latestSequence(db, product),
    db.prepare(`
      SELECT last_cursor, last_successful_exchange_at, last_successful_push_at, last_successful_pull_at
      FROM encrypted_sync_device_state WHERE product = ? AND device_id = ?
    `).bind(product, deviceId).first<{
      last_cursor: string | null
      last_successful_exchange_at: string | null
      last_successful_push_at: string | null
      last_successful_pull_at: string | null
    }>(),
  ])
  const cursor = isCursor(state?.last_cursor ?? null) ?? 0
  const pending = await db.prepare(
    'SELECT COUNT(*) AS count FROM encrypted_sync_changes WHERE product = ? AND change_seq > ?',
  ).bind(product, cursor).first<{ count: number | string }>()
  return {
    protocolVersion: 2,
    envelopeVersion: 1,
    product,
    authority: 'remote_ciphertext_authoritative',
    deviceId,
    deviceState: 'active',
    localCatchup: {
      deviceCursor: state?.last_cursor ?? null,
      latestCursor: encodeCursor(latest),
      pendingRemoteChanges: Number(pending?.count ?? 0),
    },
    lastSuccessfulExchange: state?.last_successful_exchange_at ?? null,
    lastSuccessfulPushAt: state?.last_successful_push_at ?? null,
    lastSuccessfulPullAt: state?.last_successful_pull_at ?? null,
  }
}

/**
 * Return only facts the cloud can prove without decrypting a Watch payload.
 * This is deliberately separate from `encryptedStatus`: the latter is scoped
 * to a device credential, whereas MCP callers receive an account-level view.
 */
export async function encryptedSyncOverview(
  db: D1Database,
  product: string,
): Promise<JsonRecord> {
  const [entities, deviceState, latest] = await Promise.all([
    db.prepare(`
      SELECT entity_type, COUNT(*) AS count, SUM(deleted) AS tombstones,
        MAX(revision) AS max_revision, MAX(updated_at) AS last_verified_at
      FROM encrypted_sync_entities
      WHERE product = ?
      GROUP BY entity_type
      ORDER BY entity_type
    `).bind(product).all<{
      entity_type: string
      count: number | string
      tombstones: number | string | null
      max_revision: number | string | null
      last_verified_at: string | null
    }>(),
    db.prepare(`
      SELECT COUNT(*) AS device_count, MAX(last_successful_exchange_at) AS last_verified_at
      FROM encrypted_sync_device_state
      WHERE product = ?
    `).bind(product).first<{
      device_count: number | string
      last_verified_at: string | null
    }>(),
    latestSequence(db, product),
  ])
  return {
    protocolVersion: 2,
    envelopeVersion: 1,
    product,
    authority: 'remote_ciphertext_authoritative',
    content: 'end_to_end_encrypted',
    latestCursor: encodeCursor(latest),
    deviceCount: Number(deviceState?.device_count ?? 0),
    lastVerifiedAt: deviceState?.last_verified_at ?? null,
    entityCounts: entities.results.map((row) => ({
      entityType: row.entity_type,
      count: Number(row.count),
      tombstones: Number(row.tombstones ?? 0),
      maxRevision: Number(row.max_revision ?? 0),
      lastVerifiedAt: row.last_verified_at,
    })),
  }
}

export function isValidWatchEncryptedEntity(entityType: string, entityId: string): boolean {
  return (entityType === 'plan' || entityType === 'workout') &&
    /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(entityId)
}

export function isValidEncryptedTimestamp(value: unknown): value is string {
  return isTimestamp(value)
}
