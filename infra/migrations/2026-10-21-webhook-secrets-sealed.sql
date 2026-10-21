-- Webhook signing secrets are stored SEALED (master keyring, ADR-054), not in the clear.
-- Applied to an existing orazaka_studio_db as its owner role; idempotent. This only makes room: the
-- Studio service seals the rows that are still clear when it starts (WebhookSecretMigration), because
-- the keyring is not in the database.
ALTER TABLE studio_webhook_endpoint ALTER COLUMN secret TYPE TEXT;
ALTER TABLE studio_webhook_endpoint ALTER COLUMN previous_secret TYPE TEXT;
