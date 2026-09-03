import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { DatabaseSync } from 'node:sqlite'
import test from 'node:test'

const schemaSql = readFileSync(new URL('../schema.sql', import.meta.url), 'utf8')
const migrationSql = readFileSync(new URL('../migrations/0001_sync_v1.sql', import.meta.url), 'utf8')
const encryptedMigrationSql = readFileSync(new URL('../migrations/0002_encrypted_sync_envelope_v1.sql', import.meta.url), 'utf8')
const projectionMigrationSql = readFileSync(new URL('../migrations/0003_watch_read_projection.sql', import.meta.url), 'utf8')
const projectionStateMigrationSql = readFileSync(new URL('../migrations/0004_watch_read_projection_state.sql', import.meta.url), 'utf8')
const authorityMigrationSql = readFileSync(new URL('../migrations/0005_authority_observation.sql', import.meta.url), 'utf8')
const cloudV3MigrationSql = readFileSync(new URL('../migrations/0006_cloud_v3.sql', import.meta.url), 'utf8')
const authorityTriggerSql = readFileSync(new URL('../ops/authority-checkpoint-triggers.sql', import.meta.url), 'utf8')
const now = '2026-07-28T00:00:00.000Z'
const op1 = '00000000-0000-4000-8000-000000000001'
const op2 = '00000000-0000-4000-8000-000000000002'
const reservation = '10000000-0000-4000-8000-000000000001'

function database(sql = schemaSql) {
  const db = new DatabaseSync(':memory:')
  db.exec('PRAGMA foreign_keys = ON')
  db.exec(sql)
  return db
}

function columns(db, table) {
  return db.prepare(`PRAGMA table_info(${table})`).all().map((row) => row.name)
}

function seedDevice(db, deviceId = 'watch-a', tokenHash = 'a'.repeat(64)) {
  db.prepare('INSERT INTO sync_devices (device_id, label, token_hash, created_at) VALUES (?, ?, ?, ?)')
    .run(deviceId, 'Test watch', tokenHash, now)
}

function seedOperation(db, opId = op1, deviceId = 'watch-a') {
  db.prepare(
    'INSERT INTO sync_operations (op_id, device_id, request_hash, reservation_id, created_at) '
      + 'VALUES (?, ?, ?, ?, ?)',
  ).run(opId, deviceId, 'b'.repeat(64), reservation, now)
}

test('fresh schema is idempotent and migration contains the canonical bootstrap', () => {
  assert.match(migrationSql, /BEGIN CANONICAL_SCHEMA/)
  assert.match(migrationSql, /CREATE TABLE IF NOT EXISTS watch_entities/)
  const db = database()
  seedDevice(db)
  seedOperation(db)
  assert.doesNotThrow(() => db.exec(schemaSql))
  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM sync_devices').get().count, 1)
  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM sync_operations').get().count, 1)
  assert.doesNotThrow(() => database(migrationSql))
  const migrated = database(migrationSql)
  assert.doesNotThrow(() => migrated.exec(encryptedMigrationSql))
  assert.doesNotThrow(() => migrated.exec(encryptedMigrationSql))
  assert.doesNotThrow(() => migrated.exec(projectionMigrationSql))
  assert.doesNotThrow(() => migrated.exec(projectionMigrationSql))
  assert.doesNotThrow(() => migrated.exec(projectionStateMigrationSql))
  assert.doesNotThrow(() => migrated.exec(projectionStateMigrationSql))
  assert.doesNotThrow(() => migrated.exec(authorityMigrationSql))
  assert.doesNotThrow(() => migrated.exec(authorityMigrationSql))
  assert.doesNotThrow(() => migrated.exec(cloudV3MigrationSql))
  assert.doesNotThrow(() => migrated.exec(cloudV3MigrationSql))
  assert.doesNotThrow(() => migrated.exec(authorityTriggerSql))
  assert.doesNotThrow(() => migrated.exec(authorityTriggerSql))
  assert.equal(migrated.prepare(
    "SELECT COUNT(*) AS count FROM sqlite_schema WHERE type = 'trigger' AND name LIKE 'encrypted_sync_authority_checkpoint_%'",
  ).get().count, 10)
})

test('fresh schema exposes only the Worker canonical storage model', () => {
  const db = database()
  const tables = db.prepare(
    "SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
  ).all().map((row) => row.name)
  assert.deepEqual(tables, [
    'encrypted_sync_audit', 'encrypted_sync_authority_checkpoints',
    'encrypted_sync_authority_observations', 'encrypted_sync_changes',
    'encrypted_sync_device_state', 'encrypted_sync_entities', 'encrypted_sync_operations',
    'plan_conflicts', 'snapshots', 'sync_devices', 'sync_operations', 'watch_changes',
    'watch_entities', 'watch_read_projection', 'watch_read_projection_state',
    'watch_v3_authority_checkpoints', 'watch_v3_authority_observations',
    'watch_v3_changes', 'watch_v3_command_audit', 'watch_v3_commands',
    'watch_v3_device_state', 'watch_v3_health_records', 'watch_v3_live_status', 'watch_v3_operations',
    'watch_v3_plan_groups', 'watch_v3_plan_libraries', 'watch_v3_plans',
    'watch_v3_sleep_records', 'watch_v3_workout_tombstones', 'watch_v3_workouts',
  ])
  assert.equal(tables.includes('sync_entities'), false)
  assert.equal(tables.includes('sync_changes'), false)

  assert.deepEqual(columns(db, 'sync_devices'), [
    'device_id', 'label', 'token_hash', 'created_at', 'revoked_at',
    'last_successful_exchange_at', 'last_successful_push_at', 'last_successful_pull_at', 'last_cursor',
  ])
  assert.deepEqual(columns(db, 'sync_operations'), [
    'op_id', 'device_id', 'request_hash', 'reservation_id', 'result_json', 'created_at', 'completed_at',
  ])
  assert.deepEqual(columns(db, 'watch_entities'), [
    'entity_type', 'entity_id', 'revision', 'deleted', 'payload_json', 'created_at', 'updated_at',
    'deleted_at', 'origin_device_id', 'last_operation_id',
  ])
  assert.deepEqual(columns(db, 'watch_changes'), [
    'change_seq', 'entity_type', 'entity_id', 'entity_revision', 'operation', 'payload_json',
    'changed_at', 'origin_device_id', 'operation_id',
  ])
  assert.deepEqual(columns(db, 'plan_conflicts'), [
    'conflict_id', 'entity_id', 'device_id', 'operation', 'base_revision', 'current_revision',
    'candidate_json', 'created_at',
  ])
  assert.deepEqual(columns(db, 'encrypted_sync_entities'), [
    'product', 'entity_type', 'entity_id', 'revision', 'deleted', 'ciphertext', 'nonce',
    'aad_hash', 'key_version', 'objects_json', 'created_at', 'updated_at', 'deleted_at',
    'origin_device_id', 'last_operation_id',
  ])
  assert.deepEqual(columns(db, 'encrypted_sync_changes'), [
    'change_seq', 'product', 'entity_type', 'entity_id', 'entity_revision', 'operation',
    'ciphertext', 'nonce', 'aad_hash', 'key_version', 'objects_json', 'changed_at',
    'origin_device_id', 'operation_id',
  ])
  assert.deepEqual(columns(db, 'watch_read_projection'), [
    'product', 'device_id', 'projection_type', 'projection_key', 'payload_json', 'synced_at',
  ])
  assert.deepEqual(columns(db, 'watch_read_projection_state'), [
    'product', 'device_id', 'synced_at', 'checkpoint', 'revision', 'plan_count', 'workout_count',
  ])
  assert.deepEqual(columns(db, 'encrypted_sync_authority_checkpoints'), [
    'product', 'revision', 'updated_at',
  ])
  assert.deepEqual(columns(db, 'encrypted_sync_authority_observations'), [
    'product', 'revision', 'observation_json', 'observed_at', 'expires_at',
  ])
  assert.deepEqual(columns(db, 'watch_v3_device_state'), [
    'device_id', 'owner_id', 'cursor', 'plan_bootstrapped', 'last_exchange_at',
    'last_status_at', 'created_at', 'updated_at',
  ])
  assert.deepEqual(columns(db, 'watch_v3_plan_libraries'), [
    'owner_id', 'revision', 'selected_plan_id', 'updated_at', 'updated_by_device_id',
    'last_operation_id',
  ])
  assert.deepEqual(columns(db, 'watch_v3_commands'), [
    'owner_id', 'command_id', 'request_id', 'command_type', 'expected_state',
    'control_revision', 'arguments_json', 'request_hash', 'status', 'created_at', 'expires_at',
    'delivered_at', 'completed_at', 'result_json',
  ])
  assert.deepEqual(columns(db, 'watch_v3_workout_tombstones'), [
    'owner_id', 'workout_id', 'command_id', 'deleted_at',
  ])
  assert.equal(columns(db, 'encrypted_sync_entities').includes('payload_json'), false)
  assert.equal(columns(db, 'encrypted_sync_changes').includes('payload_json'), false)
  assert.equal(columns(db, 'sync_devices').includes('token'), false, 'plaintext device token column is forbidden')
})

test('migration upgrades a snapshots-only database and purges legacy privacy-sensitive snapshots', () => {
  const db = new DatabaseSync(':memory:')
  db.exec('PRAGMA foreign_keys = ON')
  db.exec(`
    CREATE TABLE snapshots (
      kind TEXT PRIMARY KEY,
      payload TEXT NOT NULL,
      synced_at TEXT NOT NULL,
      source TEXT NOT NULL
    );
  `)
  db.prepare('INSERT INTO snapshots (kind, payload, synced_at, source) VALUES (?, ?, ?, ?)')
    .run('watch_get_status', '{"reachable":false}', now, 'legacy-agent')

  assert.doesNotThrow(() => db.exec(migrationSql))
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM snapshots WHERE kind != 'migration_audit_v1'").get().count, 0)
  assert.deepEqual(
    JSON.parse(db.prepare("SELECT payload FROM snapshots WHERE kind = 'migration_audit_v1'").get().payload),
    {
      discardedSyncEntities: 0,
      discardedSyncChanges: 0,
      legacyDevices: 0,
      devicesRequiringReprovision: 0,
      discardedLegacyOperations: 0,
      migratedDevices: 0,
      migratedOperations: 0,
    },
  )
  assert.equal(db.prepare("SELECT COUNT(*) AS count FROM sqlite_schema WHERE type = 'table' AND name = 'watch_entities'").get().count, 1)
})

test('migration replaces the rejected same-name schema and removes legacy entity/change tables', () => {
  const db = new DatabaseSync(':memory:')
  db.exec('PRAGMA foreign_keys = ON')
  db.exec(`
    CREATE TABLE snapshots (kind TEXT PRIMARY KEY, payload TEXT NOT NULL, synced_at TEXT NOT NULL, source TEXT NOT NULL);
    INSERT INTO snapshots VALUES ('watch_get_latest_sleep', '{"raw":true}', '${now}', 'legacy');
    CREATE TABLE sync_devices (
      device_id TEXT PRIMARY KEY, token_hash TEXT UNIQUE, display_name TEXT, platform TEXT,
      created_at TEXT, last_seen_at TEXT, revoked_at TEXT
    );
    CREATE TABLE sync_operations (
      operation_id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT, op_id TEXT,
      request_hash TEXT, status TEXT, response TEXT, response_status INTEGER,
      created_at TEXT, completed_at TEXT
    );
    CREATE TABLE sync_entities (
      entity_type TEXT, entity_id TEXT, revision INTEGER, payload TEXT, tombstone INTEGER,
      created_at TEXT, updated_at TEXT, deleted_at TEXT, updated_by_device_id TEXT,
      PRIMARY KEY (entity_type, entity_id)
    );
    CREATE TABLE sync_changes (
      seq INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT, op_id TEXT, ordinal INTEGER,
      entity_type TEXT, entity_id TEXT, revision INTEGER, op TEXT, payload TEXT,
      tombstone INTEGER, changed_at TEXT, deleted_at TEXT
    );
    INSERT INTO sync_devices VALUES
      ('watch-old-a', '${'d'.repeat(64)}', 'Legacy A', 'android', '${now}', '${now}', NULL),
      ('watch-old-b', '${'e'.repeat(64)}', 'Legacy B', 'android', '${now}', '${now}', '${now}');
    INSERT INTO sync_operations
      (device_id, op_id, request_hash, status, response, response_status, created_at, completed_at)
    VALUES
      ('watch-old-a', '${op1}', '${'f'.repeat(64)}', 'completed', '{"outcome":"acknowledged"}', 200, '${now}', '${now}'),
      ('watch-old-b', '${op2}', '${'1'.repeat(64)}', 'pending', NULL, NULL, '${now}', NULL);
    INSERT INTO sync_entities VALUES
      ('plan', 'legacy-plan', 1, '{"raw":"discarded"}', 0, '${now}', '${now}', NULL, 'watch-old-a');
    INSERT INTO sync_changes
      (device_id, op_id, ordinal, entity_type, entity_id, revision, op, payload, tombstone, changed_at, deleted_at)
    VALUES
      ('watch-old-a', '${op1}', 0, 'plan', 'legacy-plan', 1, 'upsert', '{"raw":"discarded"}', 0, '${now}', NULL);
  `)

  assert.doesNotThrow(() => db.exec(migrationSql))
  const tables = db.prepare(
    "SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
  ).all().map((row) => row.name)
  assert.deepEqual(tables, [
    'plan_conflicts', 'snapshots', 'sync_devices', 'sync_operations', 'watch_changes', 'watch_entities',
  ])
  assert.deepEqual(columns(db, 'sync_devices'), [
    'device_id', 'label', 'token_hash', 'created_at', 'revoked_at',
    'last_successful_exchange_at', 'last_successful_push_at', 'last_successful_pull_at', 'last_cursor',
  ])
  assert.deepEqual(columns(db, 'sync_operations'), [
    'op_id', 'device_id', 'request_hash', 'reservation_id', 'result_json', 'created_at', 'completed_at',
  ])
  const migratedDevices = db.prepare(
    'SELECT device_id, label, token_hash, revoked_at, last_successful_exchange_at FROM sync_devices ORDER BY device_id',
  ).all().map((row) => ({ ...row }))
  assert.equal(migratedDevices.length, 2)
  assert.deepEqual(
    migratedDevices.map(({ revoked_at: _revokedAt, ...device }) => device),
    [
      { device_id: 'watch-old-a', label: 'Legacy A', token_hash: 'd'.repeat(64), last_successful_exchange_at: now },
      { device_id: 'watch-old-b', label: 'Legacy B', token_hash: 'e'.repeat(64), last_successful_exchange_at: now },
    ],
  )
  assert.ok(migratedDevices.every((device) => typeof device.revoked_at === 'string'))
  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM sync_operations').get().count, 0)
  const audit = db.prepare("SELECT payload FROM snapshots WHERE kind = 'migration_audit_v1'").get()
  assert.deepEqual(JSON.parse(audit.payload), {
    discardedSyncEntities: 1,
    discardedSyncChanges: 1,
    legacyDevices: 2,
    devicesRequiringReprovision: 1,
    discardedLegacyOperations: 2,
    migratedDevices: 2,
    migratedOperations: 0,
  })
  assert.equal(db.prepare('PRAGMA foreign_keys').get().foreign_keys, 1)
})

test('stores only token hashes and reserves opId globally for deterministic replay', () => {
  const db = database()
  seedDevice(db)
  seedDevice(db, 'watch-b', 'c'.repeat(64))
  assert.throws(
    () => seedDevice(db, 'watch-c', 'a'.repeat(64)),
    /UNIQUE constraint failed/,
  )

  seedOperation(db)
  assert.throws(() => seedOperation(db, op1, 'watch-b'), /UNIQUE constraint failed/)
  assert.throws(
    () => db.prepare(
      'UPDATE sync_operations SET result_json = ?, completed_at = NULL WHERE op_id = ?',
    ).run('{"ok":true}', op1),
    /CHECK constraint failed/,
  )
  db.prepare('UPDATE sync_operations SET result_json = ?, completed_at = ? WHERE op_id = ?')
    .run('{"ok":true}', now, op1)
  assert.equal(db.prepare('SELECT result_json FROM sync_operations WHERE op_id = ?').get(op1).result_json, '{"ok":true}')
})

test('supports plan tombstones and forbids workout summary mutation or deletion', () => {
  const db = database()
  seedDevice(db)
  seedOperation(db)
  seedOperation(db, op2)
  const addEntity = db.prepare(
    'INSERT INTO watch_entities '
      + '(entity_type, entity_id, revision, deleted, payload_json, created_at, updated_at, deleted_at, origin_device_id, last_operation_id) '
      + 'VALUES (?, ?, 1, 0, ?, ?, ?, NULL, ?, ?)',
  )
  addEntity.run('plan', 'plan-1', '{"name":"easy"}', now, now, 'watch-a', op1)
  addEntity.run('workout', 'workout-1', '{"distanceMeters":5000}', now, now, 'watch-a', op1)

  db.prepare(
    "UPDATE watch_entities SET revision = 2, deleted = 1, payload_json = NULL, updated_at = ?, deleted_at = ?, last_operation_id = ? WHERE entity_type = 'plan' AND entity_id = 'plan-1'",
  ).run(now, now, op2)
  assert.deepEqual(
    { ...db.prepare("SELECT revision, deleted, payload_json, deleted_at FROM watch_entities WHERE entity_type = 'plan' AND entity_id = 'plan-1'").get() },
    { revision: 2, deleted: 1, payload_json: null, deleted_at: now },
  )
  assert.throws(
    () => db.prepare("UPDATE watch_entities SET revision = 2 WHERE entity_type = 'workout' AND entity_id = 'workout-1'").run(),
    /workout_immutable/,
  )
  assert.throws(
    () => db.prepare("DELETE FROM watch_entities WHERE entity_type = 'workout' AND entity_id = 'workout-1'").run(),
    /workout_immutable/,
  )
})

test('change feed cursor is monotonic and duplicate operations do not append twice', () => {
  const db = database()
  seedDevice(db)
  seedOperation(db)
  seedOperation(db, op2)
  const addChange = db.prepare(
    'INSERT OR IGNORE INTO watch_changes '
      + '(entity_type, entity_id, entity_revision, operation, payload_json, changed_at, origin_device_id, operation_id) '
      + 'VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
  )
  addChange.run('plan', 'plan-1', 1, 'upsert', '{}', now, 'watch-a', op1)
  addChange.run('plan', 'plan-1', 2, 'delete', null, now, 'watch-a', op2)
  addChange.run('plan', 'plan-2', 1, 'upsert', '{}', now, 'watch-a', op2)

  assert.deepEqual(
    db.prepare('SELECT change_seq, operation FROM watch_changes WHERE change_seq > ? ORDER BY change_seq LIMIT ?')
      .all(0, 10).map((row) => ({ ...row })),
    [{ change_seq: 1, operation: 'upsert' }, { change_seq: 2, operation: 'delete' }],
  )
  assert.deepEqual(
    db.prepare('SELECT change_seq FROM watch_changes WHERE change_seq > ? ORDER BY change_seq LIMIT ?')
      .all(1, 1).map((row) => ({ ...row })),
    [{ change_seq: 2 }],
  )
})

test('plan OCC conflicts preserve the candidate beside current revision metadata', () => {
  const db = database()
  seedDevice(db)
  seedOperation(db)
  db.prepare(
    'INSERT INTO plan_conflicts '
      + '(conflict_id, entity_id, device_id, operation, base_revision, current_revision, candidate_json, created_at) '
      + 'VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
  ).run(op1, 'plan-1', 'watch-a', 'upsert', 1, 2, '{"name":"candidate"}', now)
  assert.deepEqual(
    { ...db.prepare('SELECT base_revision, current_revision, candidate_json FROM plan_conflicts WHERE conflict_id = ?').get(op1) },
    { base_revision: 1, current_revision: 2, candidate_json: '{"name":"candidate"}' },
  )
})
