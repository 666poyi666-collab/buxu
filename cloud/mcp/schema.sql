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

-- SyncEnvelopeV1 is a separate encrypted data plane. The V1 plan/workout
-- tables remain migration-only; no business plaintext is accepted by V2.
CREATE TABLE IF NOT EXISTS encrypted_sync_entities (
  product TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  deleted INTEGER NOT NULL,
  ciphertext TEXT,
  nonce TEXT,
  aad_hash TEXT NOT NULL,
  key_version INTEGER NOT NULL,
  objects_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  deleted_at TEXT,
  origin_device_id TEXT NOT NULL,
  last_operation_id TEXT NOT NULL,
  PRIMARY KEY (product, entity_type, entity_id),
  FOREIGN KEY (origin_device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(product) BETWEEN 2 AND 64),
  CHECK (entity_type IN ('plan', 'workout')),
  CHECK (length(entity_id) BETWEEN 1 AND 128),
  CHECK (revision >= 1),
  CHECK (deleted IN (0, 1)),
  CHECK (key_version >= 1),
  CHECK (length(aad_hash) = 64 AND aad_hash NOT GLOB '*[^0-9a-f]*'),
  CHECK (json_valid(objects_json)),
  CHECK (
    (deleted = 0 AND ciphertext IS NOT NULL AND nonce IS NOT NULL AND deleted_at IS NULL)
    OR (deleted = 1 AND ciphertext IS NULL AND nonce IS NULL AND deleted_at IS NOT NULL AND objects_json = '[]')
  )
);
CREATE INDEX IF NOT EXISTS idx_encrypted_sync_entities_updated
  ON encrypted_sync_entities(product, updated_at DESC);

CREATE TABLE IF NOT EXISTS encrypted_sync_operations (
  product TEXT NOT NULL,
  op_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  reservation_id TEXT NOT NULL,
  result_json TEXT,
  created_at TEXT NOT NULL,
  completed_at TEXT,
  PRIMARY KEY (product, op_id),
  FOREIGN KEY (device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(product) BETWEEN 2 AND 64),
  CHECK (length(op_id) = 36),
  CHECK (length(request_hash) = 64 AND request_hash NOT GLOB '*[^0-9a-f]*'),
  CHECK (length(reservation_id) = 36),
  CHECK (result_json IS NULL OR json_valid(result_json)),
  CHECK (
    (result_json IS NULL AND completed_at IS NULL)
    OR (result_json IS NOT NULL AND completed_at IS NOT NULL)
  )
);
CREATE INDEX IF NOT EXISTS idx_encrypted_sync_operations_device
  ON encrypted_sync_operations(product, device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS encrypted_sync_changes (
  change_seq INTEGER PRIMARY KEY AUTOINCREMENT,
  product TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  entity_revision INTEGER NOT NULL,
  operation TEXT NOT NULL CHECK(operation IN ('upsert', 'delete')),
  ciphertext TEXT,
  nonce TEXT,
  aad_hash TEXT NOT NULL,
  key_version INTEGER NOT NULL,
  objects_json TEXT NOT NULL,
  changed_at TEXT NOT NULL,
  origin_device_id TEXT NOT NULL,
  operation_id TEXT NOT NULL,
  FOREIGN KEY (origin_device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(product) BETWEEN 2 AND 64),
  CHECK (entity_type IN ('plan', 'workout')),
  CHECK (length(entity_id) BETWEEN 1 AND 128),
  CHECK (entity_revision >= 1),
  CHECK (key_version >= 1),
  CHECK (length(aad_hash) = 64 AND aad_hash NOT GLOB '*[^0-9a-f]*'),
  CHECK (json_valid(objects_json)),
  CHECK (
    (operation = 'upsert' AND ciphertext IS NOT NULL AND nonce IS NOT NULL)
    OR (operation = 'delete' AND ciphertext IS NULL AND nonce IS NULL AND objects_json = '[]')
  )
);
CREATE INDEX IF NOT EXISTS idx_encrypted_sync_changes_cursor
  ON encrypted_sync_changes(product, change_seq);
CREATE UNIQUE INDEX IF NOT EXISTS idx_encrypted_sync_changes_operation
  ON encrypted_sync_changes(product, operation_id);

CREATE TABLE IF NOT EXISTS encrypted_sync_device_state (
  product TEXT NOT NULL,
  device_id TEXT NOT NULL,
  last_cursor TEXT,
  last_successful_exchange_at TEXT,
  last_successful_push_at TEXT,
  last_successful_pull_at TEXT,
  PRIMARY KEY (product, device_id),
  FOREIGN KEY (device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(product) BETWEEN 2 AND 64),
  CHECK (last_cursor IS NULL OR (substr(last_cursor, 1, 1) = 'c' AND last_cursor NOT GLOB '*[^c0-9a-z]*'))
);

CREATE TABLE IF NOT EXISTS encrypted_sync_audit (
  audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
  product TEXT NOT NULL,
  event_at TEXT NOT NULL,
  device_id TEXT NOT NULL,
  mutation_count INTEGER NOT NULL,
  acknowledged_count INTEGER NOT NULL,
  conflict_count INTEGER NOT NULL,
  FOREIGN KEY (device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(product) BETWEEN 2 AND 64),
  CHECK (mutation_count >= 0),
  CHECK (acknowledged_count >= 0),
  CHECK (conflict_count >= 0)
);
CREATE INDEX IF NOT EXISTS idx_encrypted_sync_audit_time
  ON encrypted_sync_audit(product, event_at DESC);

-- This is the only deliberately decryptable Watch content surface. Input is accepted solely
-- on the device-authenticated V2 exchange route and is validated by an exact field allowlist.
CREATE TABLE IF NOT EXISTS watch_read_projection (
  product TEXT NOT NULL,
  device_id TEXT NOT NULL,
  projection_type TEXT NOT NULL,
  projection_key TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  synced_at TEXT NOT NULL,
  PRIMARY KEY (product, device_id, projection_type, projection_key),
  FOREIGN KEY (device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(product) BETWEEN 2 AND 64),
  CHECK (projection_type IN ('plan', 'workout')),
  CHECK (
    (projection_type = 'plan' AND projection_key GLOB 'p:*' AND length(projection_key) = 66)
    OR (projection_type = 'workout' AND projection_key GLOB 'w:*' AND length(projection_key) = 66)
  ),
  CHECK (json_valid(payload_json))
);
CREATE INDEX IF NOT EXISTS idx_watch_read_projection_latest
  ON watch_read_projection(product, projection_type, synced_at DESC);

-- Materialization receipt is separate from row storage so an authenticated empty projection is
-- distinguishable from a device that has never supplied a projection.
CREATE TABLE IF NOT EXISTS watch_read_projection_state (
  product TEXT NOT NULL,
  device_id TEXT NOT NULL,
  synced_at TEXT NOT NULL,
  checkpoint TEXT NOT NULL,
  revision INTEGER NOT NULL,
  plan_count INTEGER NOT NULL,
  workout_count INTEGER NOT NULL,
  PRIMARY KEY (product, device_id),
  FOREIGN KEY (device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(product) BETWEEN 2 AND 64),
  CHECK (substr(checkpoint, 1, 1) = 'c' AND checkpoint NOT GLOB '*[^c0-9a-z]*'),
  CHECK (revision >= 0),
  CHECK (plan_count BETWEEN 0 AND 500),
  CHECK (workout_count BETWEEN 0 AND 500)
);
CREATE INDEX IF NOT EXISTS idx_watch_read_projection_state_latest
  ON watch_read_projection_state(product, synced_at DESC);

-- Authority observations are immutable snapshots of real D1 state transitions. The checkpoint
-- never advances when the observation endpoint is read, so a revision has stable truth and time
-- fields (and therefore a stable observationHash at the central signing authority).
CREATE TABLE IF NOT EXISTS encrypted_sync_authority_checkpoints (
  product TEXT NOT NULL,
  revision INTEGER PRIMARY KEY AUTOINCREMENT,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_encrypted_sync_authority_checkpoints_product
  ON encrypted_sync_authority_checkpoints(product, revision DESC);

CREATE TABLE IF NOT EXISTS encrypted_sync_authority_observations (
  product TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK (revision >= 1),
  observation_json TEXT NOT NULL CHECK (json_valid(observation_json)),
  observed_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  PRIMARY KEY (product, revision)
);

CREATE TRIGGER IF NOT EXISTS encrypted_sync_authority_checkpoint_change
AFTER INSERT ON encrypted_sync_changes
WHEN NEW.product = 'watch'
BEGIN
  INSERT INTO encrypted_sync_authority_checkpoints (product, updated_at)
  VALUES (NEW.product, NEW.changed_at);
END;

CREATE TRIGGER IF NOT EXISTS encrypted_sync_authority_checkpoint_device_state_insert
AFTER INSERT ON encrypted_sync_device_state
WHEN NEW.product = 'watch'
BEGIN
  INSERT INTO encrypted_sync_authority_checkpoints (product, updated_at)
  VALUES (NEW.product, NEW.last_successful_exchange_at);
END;

CREATE TRIGGER IF NOT EXISTS encrypted_sync_authority_checkpoint_device_state_update
AFTER UPDATE OF last_cursor, last_successful_exchange_at, last_successful_push_at,
  last_successful_pull_at ON encrypted_sync_device_state
WHEN NEW.product = 'watch'
BEGIN
  INSERT INTO encrypted_sync_authority_checkpoints (product, updated_at)
  VALUES (NEW.product, NEW.last_successful_exchange_at);
END;

CREATE TRIGGER IF NOT EXISTS encrypted_sync_authority_checkpoint_operation_insert
AFTER INSERT ON encrypted_sync_operations
WHEN NEW.product = 'watch'
BEGIN
  INSERT INTO encrypted_sync_authority_checkpoints (product, updated_at)
  VALUES (NEW.product, NEW.created_at);
END;

CREATE TRIGGER IF NOT EXISTS encrypted_sync_authority_checkpoint_operation_complete
AFTER UPDATE OF result_json, completed_at ON encrypted_sync_operations
WHEN NEW.product = 'watch'
BEGIN
  INSERT INTO encrypted_sync_authority_checkpoints (product, updated_at)
  VALUES (NEW.product, COALESCE(NEW.completed_at, NEW.created_at));
END;

CREATE TRIGGER IF NOT EXISTS encrypted_sync_authority_checkpoint_audit
AFTER INSERT ON encrypted_sync_audit
WHEN NEW.product = 'watch'
BEGIN
  INSERT INTO encrypted_sync_authority_checkpoints (product, updated_at)
  VALUES (NEW.product, NEW.event_at);
END;

CREATE TRIGGER IF NOT EXISTS encrypted_sync_authority_checkpoint_projection_insert
AFTER INSERT ON watch_read_projection_state
WHEN NEW.product = 'watch'
BEGIN
  INSERT INTO encrypted_sync_authority_checkpoints (product, updated_at)
  VALUES (NEW.product, NEW.synced_at);
END;

CREATE TRIGGER IF NOT EXISTS encrypted_sync_authority_checkpoint_projection_update
AFTER UPDATE OF synced_at, checkpoint, revision, plan_count, workout_count
ON watch_read_projection_state
WHEN NEW.product = 'watch'
BEGIN
  INSERT INTO encrypted_sync_authority_checkpoints (product, updated_at)
  VALUES (NEW.product, NEW.synced_at);
END;

CREATE TRIGGER IF NOT EXISTS encrypted_sync_authority_checkpoint_device_insert
AFTER INSERT ON sync_devices
BEGIN
  INSERT INTO encrypted_sync_authority_checkpoints (product, updated_at)
  VALUES ('watch', NEW.created_at);
END;

CREATE TRIGGER IF NOT EXISTS encrypted_sync_authority_checkpoint_device_revoke
AFTER UPDATE OF revoked_at ON sync_devices
WHEN OLD.revoked_at IS NOT NEW.revoked_at
BEGIN
  INSERT INTO encrypted_sync_authority_checkpoints (product, updated_at)
  VALUES ('watch', COALESCE(NEW.revoked_at, NEW.created_at));
END;

-- Watch Cloud V3 server-readable sync plane.
-- V2 remains available during migration; this migration is additive.

CREATE TABLE IF NOT EXISTS watch_v3_device_state (
  device_id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  cursor INTEGER NOT NULL DEFAULT 0,
  plan_bootstrapped INTEGER NOT NULL DEFAULT 0 CHECK (plan_bootstrapped IN (0, 1)),
  last_exchange_at TEXT,
  last_status_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(owner_id) BETWEEN 1 AND 128),
  CHECK (cursor >= 0)
);
CREATE INDEX IF NOT EXISTS idx_watch_v3_device_owner
  ON watch_v3_device_state(owner_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS watch_v3_plan_libraries (
  owner_id TEXT PRIMARY KEY,
  revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
  selected_plan_id TEXT,
  updated_at TEXT NOT NULL,
  updated_by_device_id TEXT,
  last_operation_id TEXT NOT NULL,
  FOREIGN KEY (updated_by_device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS watch_v3_plan_groups (
  owner_id TEXT NOT NULL,
  group_id TEXT NOT NULL,
  name TEXT NOT NULL,
  sort_order INTEGER NOT NULL,
  payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
  updated_at TEXT NOT NULL,
  PRIMARY KEY (owner_id, group_id),
  FOREIGN KEY (owner_id) REFERENCES watch_v3_plan_libraries(owner_id) ON DELETE CASCADE,
  CHECK (length(group_id) BETWEEN 1 AND 128),
  CHECK (length(name) BETWEEN 1 AND 200)
);
CREATE INDEX IF NOT EXISTS idx_watch_v3_groups_order
  ON watch_v3_plan_groups(owner_id, sort_order, group_id);

CREATE TABLE IF NOT EXISTS watch_v3_plans (
  owner_id TEXT NOT NULL,
  plan_id TEXT NOT NULL,
  group_id TEXT,
  name TEXT NOT NULL,
  sort_order INTEGER NOT NULL,
  payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
  updated_at TEXT NOT NULL,
  PRIMARY KEY (owner_id, plan_id),
  FOREIGN KEY (owner_id) REFERENCES watch_v3_plan_libraries(owner_id) ON DELETE CASCADE,
  CHECK (length(plan_id) BETWEEN 1 AND 128),
  CHECK (group_id IS NULL OR length(group_id) BETWEEN 1 AND 128),
  CHECK (length(name) BETWEEN 1 AND 200)
);
CREATE INDEX IF NOT EXISTS idx_watch_v3_plans_order
  ON watch_v3_plans(owner_id, group_id, sort_order, plan_id);

CREATE TABLE IF NOT EXISTS watch_v3_workouts (
  owner_id TEXT NOT NULL,
  workout_id TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
  started_at TEXT NOT NULL,
  ended_at TEXT NOT NULL,
  duration_ms INTEGER NOT NULL CHECK (duration_ms >= 0),
  distance_meters REAL NOT NULL CHECK (distance_meters >= 0),
  steps INTEGER NOT NULL CHECK (steps >= 0),
  average_heart_rate REAL,
  maximum_heart_rate REAL,
  minimum_heart_rate REAL,
  tombstoned INTEGER NOT NULL DEFAULT 0 CHECK (tombstoned IN (0, 1)),
  created_at TEXT NOT NULL,
  tombstoned_at TEXT,
  tombstone_command_id TEXT,
  origin_device_id TEXT NOT NULL,
  created_operation_id TEXT NOT NULL,
  PRIMARY KEY (owner_id, workout_id),
  FOREIGN KEY (origin_device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(payload_hash) = 64 AND payload_hash NOT GLOB '*[^0-9a-f]*'),
  CHECK ((tombstoned = 0 AND tombstoned_at IS NULL) OR (tombstoned = 1 AND tombstoned_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_watch_v3_workouts_started
  ON watch_v3_workouts(owner_id, tombstoned, started_at DESC);

CREATE TABLE IF NOT EXISTS watch_v3_workout_tombstones (
  owner_id TEXT NOT NULL,
  workout_id TEXT NOT NULL,
  command_id TEXT NOT NULL,
  deleted_at TEXT NOT NULL,
  PRIMARY KEY (owner_id, workout_id),
  UNIQUE (owner_id, command_id)
);

CREATE TABLE IF NOT EXISTS watch_v3_sleep_records (
  owner_id TEXT NOT NULL,
  record_id TEXT NOT NULL,
  source_revision TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
  start_time TEXT NOT NULL,
  end_time TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  origin_device_id TEXT NOT NULL,
  PRIMARY KEY (owner_id, record_id),
  FOREIGN KEY (origin_device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(record_id) BETWEEN 1 AND 128),
  CHECK (length(source_revision) BETWEEN 1 AND 128),
  CHECK (length(payload_hash) = 64 AND payload_hash NOT GLOB '*[^0-9a-f]*')
);
CREATE INDEX IF NOT EXISTS idx_watch_v3_sleep_end
  ON watch_v3_sleep_records(owner_id, end_time DESC);

CREATE TABLE IF NOT EXISTS watch_v3_health_records (
  owner_id TEXT NOT NULL,
  record_id TEXT NOT NULL,
  source_revision TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
  kind TEXT NOT NULL,
  start_time TEXT NOT NULL,
  end_time TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  origin_device_id TEXT NOT NULL,
  PRIMARY KEY (owner_id, record_id),
  FOREIGN KEY (origin_device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(record_id) BETWEEN 1 AND 128),
  CHECK (length(source_revision) BETWEEN 1 AND 128),
  CHECK (length(payload_hash) = 64 AND payload_hash NOT GLOB '*[^0-9a-f]*'),
  CHECK (length(kind) BETWEEN 1 AND 40)
);
CREATE INDEX IF NOT EXISTS idx_watch_v3_health_end
  ON watch_v3_health_records(owner_id, end_time DESC);

CREATE TABLE IF NOT EXISTS watch_v3_live_status (
  owner_id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  status_revision INTEGER NOT NULL CHECK (status_revision >= 0),
  payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
  observed_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS watch_v3_changes (
  change_seq INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_id TEXT NOT NULL,
  change_type TEXT NOT NULL CHECK (change_type IN ('plan_library', 'workout', 'workout_tombstone', 'sleep', 'command')),
  entity_id TEXT NOT NULL,
  entity_revision INTEGER NOT NULL CHECK (entity_revision >= 0),
  operation TEXT NOT NULL CHECK (operation IN ('upsert', 'delete')),
  payload_json TEXT CHECK (payload_json IS NULL OR json_valid(payload_json)),
  changed_at TEXT NOT NULL,
  origin_device_id TEXT,
  operation_id TEXT,
  FOREIGN KEY (origin_device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK ((operation = 'upsert' AND payload_json IS NOT NULL) OR operation = 'delete')
);
CREATE INDEX IF NOT EXISTS idx_watch_v3_changes_owner_cursor
  ON watch_v3_changes(owner_id, change_seq);
CREATE UNIQUE INDEX IF NOT EXISTS idx_watch_v3_changes_operation
  ON watch_v3_changes(owner_id, operation_id)
  WHERE operation_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS watch_v3_operations (
  owner_id TEXT NOT NULL,
  operation_id TEXT NOT NULL,
  device_id TEXT,
  operation_type TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  result_json TEXT NOT NULL CHECK (json_valid(result_json)),
  created_at TEXT NOT NULL,
  PRIMARY KEY (owner_id, operation_id),
  FOREIGN KEY (device_id) REFERENCES sync_devices(device_id) ON DELETE RESTRICT,
  CHECK (length(operation_id) = 36),
  CHECK (length(request_hash) = 64 AND request_hash NOT GLOB '*[^0-9a-f]*')
);
CREATE INDEX IF NOT EXISTS idx_watch_v3_operations_device
  ON watch_v3_operations(device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS watch_v3_commands (
  owner_id TEXT NOT NULL,
  command_id TEXT NOT NULL,
  request_id TEXT NOT NULL,
  command_type TEXT NOT NULL CHECK (command_type IN ('start', 'pause', 'resume', 'stop', 'select_plan', 'delete_workout')),
  expected_state TEXT,
  control_revision INTEGER NOT NULL CHECK (control_revision >= 0),
  arguments_json TEXT NOT NULL CHECK (json_valid(arguments_json)),
  request_hash TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending', 'delivered', 'succeeded', 'failed', 'expired')),
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  delivered_at TEXT,
  completed_at TEXT,
  result_json TEXT CHECK (result_json IS NULL OR json_valid(result_json)),
  PRIMARY KEY (owner_id, command_id),
  UNIQUE (owner_id, request_id)
);
CREATE INDEX IF NOT EXISTS idx_watch_v3_commands_pending
  ON watch_v3_commands(owner_id, status, expires_at, created_at);

CREATE TABLE IF NOT EXISTS watch_v3_command_audit (
  audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_id TEXT NOT NULL,
  command_id TEXT NOT NULL,
  event TEXT NOT NULL CHECK (event IN ('created', 'delivered', 'acknowledged', 'failed', 'expired', 'late_result_rejected')),
  event_at TEXT NOT NULL,
  detail_json TEXT NOT NULL CHECK (json_valid(detail_json)),
  FOREIGN KEY (owner_id, command_id) REFERENCES watch_v3_commands(owner_id, command_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_watch_v3_command_audit_command
  ON watch_v3_command_audit(owner_id, command_id, audit_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_watch_v3_command_audit_once
  ON watch_v3_command_audit(owner_id, command_id, event);

CREATE TABLE IF NOT EXISTS watch_v3_authority_checkpoints (
  owner_id TEXT NOT NULL,
  revision INTEGER PRIMARY KEY AUTOINCREMENT,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_watch_v3_authority_checkpoints_owner
  ON watch_v3_authority_checkpoints(owner_id, revision DESC);

CREATE TABLE IF NOT EXISTS watch_v3_authority_observations (
  owner_id TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK (revision >= 1),
  observation_json TEXT NOT NULL CHECK (json_valid(observation_json)),
  observed_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  PRIMARY KEY (owner_id, revision)
);

CREATE TRIGGER IF NOT EXISTS watch_v3_authority_change
AFTER INSERT ON watch_v3_changes
BEGIN
  INSERT INTO watch_v3_authority_checkpoints (owner_id, updated_at)
  VALUES (NEW.owner_id, NEW.changed_at);
END;

CREATE TRIGGER IF NOT EXISTS watch_v3_authority_device_state
AFTER UPDATE OF cursor, last_exchange_at, last_status_at ON watch_v3_device_state
BEGIN
  INSERT INTO watch_v3_authority_checkpoints (owner_id, updated_at)
  VALUES (NEW.owner_id, NEW.updated_at);
END;
