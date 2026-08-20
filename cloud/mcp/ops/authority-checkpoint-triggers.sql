-- Apply immediately after migration 0005 with `wrangler d1 execute --remote --file`.
-- D1's migration query endpoint cannot batch compound trigger bodies with its bookkeeping INSERT;
-- the file-import endpoint executes this audited, idempotent schema adjunct atomically.
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
