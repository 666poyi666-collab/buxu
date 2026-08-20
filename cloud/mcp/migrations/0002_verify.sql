SELECT COUNT(*) AS incomplete_encrypted_entities
FROM encrypted_sync_entities
WHERE (deleted = 0 AND (ciphertext IS NULL OR nonce IS NULL OR json_valid(objects_json) = 0))
   OR (deleted = 1 AND (ciphertext IS NOT NULL OR nonce IS NOT NULL OR objects_json <> '[]'));

SELECT COUNT(*) AS incomplete_encrypted_changes
FROM encrypted_sync_changes
WHERE (operation = 'upsert' AND (ciphertext IS NULL OR nonce IS NULL OR json_valid(objects_json) = 0))
   OR (operation = 'delete' AND (ciphertext IS NOT NULL OR nonce IS NOT NULL OR objects_json <> '[]'));

SELECT COUNT(*) AS incomplete_encrypted_operations
FROM encrypted_sync_operations
WHERE (result_json IS NULL AND completed_at IS NOT NULL)
   OR (result_json IS NOT NULL AND completed_at IS NULL);
