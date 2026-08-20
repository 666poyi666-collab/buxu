-- The encrypted plane is additive. Existing V1 plaintext entities are not
-- copied automatically; a client must encrypt and acknowledge every record.
SELECT COUNT(*) AS legacy_plaintext_entities
FROM watch_entities;

SELECT COUNT(*) AS legacy_plaintext_changes
FROM watch_changes;
