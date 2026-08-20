-- Minimal device-authenticated plaintext projection for OAuth MCP reads.
-- Canonical plan/workout bodies remain exclusively in encrypted_sync_*.
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
