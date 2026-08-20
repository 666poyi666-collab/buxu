-- Receipt for exact-field Watch read projection materialization.
-- Empty plan/workout arrays still produce a receipt, allowing readiness and authority observation
-- to distinguish a verified empty data set from one that has never been materialized.
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
