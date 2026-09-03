-- Cloud V3 health summary records. Mirrors the manufacturers DailyActivity/HeartRate/HeartRateStats
-- records surfaced by the watch. The payload_json keeps the full summary; the small scalar columns
-- are for readable paging and querying without parsing every blob.
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
