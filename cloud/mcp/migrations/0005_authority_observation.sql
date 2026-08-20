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
