-- One-time reconciliation for historical, incompatible sync experiments.
-- Safe credential and idempotency fields are projected into the canonical
-- tables. Raw legacy entity/change payloads are deliberately discarded and
-- only their counts survive in a sanitized migration audit record. Cloudflare
-- records applied D1 migrations, so this prelude runs once per database.
PRAGMA foreign_keys = OFF;

CREATE TABLE IF NOT EXISTS snapshots (
  kind TEXT PRIMARY KEY,
  payload TEXT NOT NULL,
  synced_at TEXT NOT NULL,
  source TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_devices (
  device_id TEXT PRIMARY KEY,
  token_hash TEXT,
  display_name TEXT,
  platform TEXT,
  created_at TEXT,
  last_seen_at TEXT,
  revoked_at TEXT
);

CREATE TABLE IF NOT EXISTS sync_operations (
  operation_id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT,
  op_id TEXT,
  request_hash TEXT,
  status TEXT,
  response TEXT,
  response_status INTEGER,
  created_at TEXT,
  completed_at TEXT
);

CREATE TABLE IF NOT EXISTS sync_entities (legacy_marker INTEGER);
CREATE TABLE IF NOT EXISTS sync_changes (legacy_marker INTEGER);

DROP TABLE IF EXISTS __watch_migration_counts;
CREATE TABLE __watch_migration_counts AS
SELECT
  (SELECT COUNT(*) FROM sync_entities) AS discarded_entities,
  (SELECT COUNT(*) FROM sync_changes) AS discarded_changes,
  (SELECT COUNT(*) FROM sync_devices) AS legacy_devices,
  (SELECT COUNT(*) FROM sync_devices WHERE revoked_at IS NULL) AS devices_requiring_reprovision,
  (SELECT COUNT(*) FROM sync_operations) AS discarded_operations;

DROP TABLE sync_changes;
DROP TABLE sync_entities;

DROP TABLE IF EXISTS __watch_legacy_sync_operations;
ALTER TABLE sync_operations RENAME TO __watch_legacy_sync_operations;

DROP TABLE IF EXISTS __watch_legacy_sync_devices;
ALTER TABLE sync_devices RENAME TO __watch_legacy_sync_devices;

-- BEGIN CANONICAL_SCHEMA
-- Watch Cloud D1 schema. This file is safe for fresh databases and repeated
-- local bootstrap runs after the legacy migration has completed.

CREATE TABLE IF NOT EXISTS snapshots (
  kind TEXT PRIMARY KEY,
  payload TEXT NOT NULL,
  synced_at TEXT NOT NULL,
  source TEXT NOT NULL
);

-- Snapshot ingestion is permanently retired. Legacy rows may contain raw
-- sleep, heart, route, or live-status data that the canonical entity plane
-- intentionally rejects, so an upgrade removes them before MCP can read them.
DELETE FROM snapshots;

CREATE TABLE IF NOT EXISTS sync_devices (
  device_id TEXT NOT NULL PRIMARY KEY,
  label TEXT,
  token_hash TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,
  revoked_at TEXT,
  last_successful_exchange_at TEXT,
  last_successful_push_at TEXT,
  last_successful_pull_at TEXT,
  last_cursor TEXT,
  CHECK (length(device_id) BETWEEN 3 AND 128),
  CHECK (label IS NULL OR length(label) <= 80),
  CHECK (length(token_hash) = 64)
);

CREATE INDEX IF NOT EXISTS idx_sync_devices_active_token
  ON sync_devices(token_hash)
  WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS sync_operations (
  op_id TEXT NOT NULL PRIMARY KEY,
  device_id TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  reservation_id TEXT NOT NULL,
  result_json TEXT CHECK (result_json IS NULL OR json_valid(result_json)),
  created_at TEXT NOT NULL,
  completed_at TEXT,
  FOREIGN KEY (device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(op_id) = 36),
  CHECK (length(request_hash) = 64),
  CHECK (length(reservation_id) = 36),
  CHECK (
    (result_json IS NULL AND completed_at IS NULL)
    OR (result_json IS NOT NULL AND completed_at IS NOT NULL)
  )
);

CREATE INDEX IF NOT EXISTS idx_sync_operations_device_created
  ON sync_operations(device_id, created_at, op_id);

CREATE TABLE IF NOT EXISTS watch_entities (
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1)),
  payload_json TEXT CHECK (payload_json IS NULL OR json_valid(payload_json)),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  deleted_at TEXT,
  origin_device_id TEXT NOT NULL,
  last_operation_id TEXT NOT NULL,
  PRIMARY KEY (entity_type, entity_id),
  FOREIGN KEY (origin_device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  FOREIGN KEY (last_operation_id) REFERENCES sync_operations(op_id) ON DELETE RESTRICT,
  CHECK (entity_type IN ('plan', 'workout')),
  CHECK (length(entity_id) BETWEEN 1 AND 128),
  CHECK (revision >= 1),
  CHECK (
    (deleted = 0 AND payload_json IS NOT NULL AND deleted_at IS NULL)
    OR (deleted = 1 AND payload_json IS NULL AND deleted_at IS NOT NULL)
  )
);

CREATE INDEX IF NOT EXISTS idx_watch_entities_live_type_updated
  ON watch_entities(entity_type, updated_at DESC, entity_id)
  WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_watch_entities_updated
  ON watch_entities(updated_at, entity_type, entity_id);

CREATE TABLE IF NOT EXISTS watch_changes (
  change_seq INTEGER PRIMARY KEY AUTOINCREMENT,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  entity_revision INTEGER NOT NULL,
  operation TEXT NOT NULL CHECK (operation IN ('upsert', 'delete')),
  payload_json TEXT CHECK (payload_json IS NULL OR json_valid(payload_json)),
  changed_at TEXT NOT NULL,
  origin_device_id TEXT NOT NULL,
  operation_id TEXT,
  FOREIGN KEY (origin_device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  FOREIGN KEY (operation_id) REFERENCES sync_operations(op_id) ON DELETE RESTRICT,
  CHECK (entity_type IN ('plan', 'workout')),
  CHECK (length(entity_id) BETWEEN 1 AND 128),
  CHECK (entity_revision >= 1),
  CHECK (
    (operation = 'upsert' AND payload_json IS NOT NULL)
    OR (operation = 'delete' AND payload_json IS NULL)
  )
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_watch_changes_operation
  ON watch_changes(operation_id)
  WHERE operation_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_watch_changes_entity
  ON watch_changes(entity_type, entity_id, change_seq);

CREATE INDEX IF NOT EXISTS idx_watch_changes_device_seq
  ON watch_changes(origin_device_id, change_seq);

CREATE TABLE IF NOT EXISTS plan_conflicts (
  conflict_id TEXT NOT NULL PRIMARY KEY,
  entity_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  operation TEXT NOT NULL CHECK (operation IN ('upsert', 'delete')),
  base_revision INTEGER NOT NULL,
  current_revision INTEGER,
  candidate_json TEXT CHECK (candidate_json IS NULL OR json_valid(candidate_json)),
  created_at TEXT NOT NULL,
  FOREIGN KEY (conflict_id) REFERENCES sync_operations(op_id) ON DELETE RESTRICT,
  FOREIGN KEY (device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(entity_id) BETWEEN 1 AND 128),
  CHECK (base_revision >= 0),
  CHECK (current_revision IS NULL OR current_revision >= 1),
  CHECK (
    (operation = 'upsert' AND candidate_json IS NOT NULL)
    OR (operation = 'delete' AND candidate_json IS NULL)
  )
);

CREATE INDEX IF NOT EXISTS idx_plan_conflicts_device_created
  ON plan_conflicts(device_id, created_at, conflict_id);

CREATE INDEX IF NOT EXISTS idx_plan_conflicts_entity_created
  ON plan_conflicts(entity_id, created_at, conflict_id);

-- Workout summaries are append-only facts. Duplicate opIds replay from
-- sync_operations; a distinct operation cannot mutate or erase one.
CREATE TRIGGER IF NOT EXISTS trg_watch_workout_immutable_update
BEFORE UPDATE ON watch_entities
WHEN OLD.entity_type = 'workout'
BEGIN
  SELECT RAISE(ABORT, 'workout_immutable');
END;

CREATE TRIGGER IF NOT EXISTS trg_watch_workout_immutable_delete
BEFORE DELETE ON watch_entities
WHEN OLD.entity_type = 'workout'
BEGIN
  SELECT RAISE(ABORT, 'workout_immutable');
END;

INSERT OR IGNORE INTO sync_devices (
  device_id, label, token_hash, created_at, revoked_at,
  last_successful_exchange_at, last_successful_push_at,
  last_successful_pull_at, last_cursor
)
SELECT
  device_id,
  CASE WHEN display_name IS NULL THEN NULL ELSE substr(display_name, 1, 80) END,
  token_hash,
  COALESCE(created_at, '1970-01-01T00:00:00.000Z'),
  COALESCE(revoked_at, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  last_seen_at,
  last_seen_at,
  last_seen_at,
  NULL
FROM __watch_legacy_sync_devices
WHERE length(device_id) BETWEEN 3 AND 128
  AND length(token_hash) = 64;

INSERT OR REPLACE INTO snapshots (kind, payload, synced_at, source)
SELECT
  'migration_audit_v1',
  json_object(
    'discardedSyncEntities', discarded_entities,
    'discardedSyncChanges', discarded_changes,
    'legacyDevices', legacy_devices,
    'devicesRequiringReprovision', devices_requiring_reprovision,
    'discardedLegacyOperations', discarded_operations,
    'migratedDevices', (SELECT COUNT(*) FROM sync_devices),
    'migratedOperations', (SELECT COUNT(*) FROM sync_operations)
  ),
  strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
  'migration-0001-sync-v1'
FROM __watch_migration_counts;

DROP TABLE IF EXISTS __watch_legacy_sync_operations;
DROP TABLE IF EXISTS __watch_legacy_sync_devices;
DROP TABLE IF EXISTS __watch_migration_counts;
PRAGMA foreign_keys = ON;
