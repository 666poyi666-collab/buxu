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
