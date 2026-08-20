-- SyncEnvelopeV1 encrypted data plane. This migration is additive: V1 rows
-- remain intact for a deliberate client-side encryption migration and are never
-- copied into the encrypted tables as plaintext.

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
