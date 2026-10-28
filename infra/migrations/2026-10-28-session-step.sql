-- SESSION step (ADR-093, krizaka/orazaka#450): the code agent's run stays RUNNING while the Mac sends turns, and closes on
-- the client's « finish » or after an idle TTL read from this table. Applied to an existing orazaka_studio_db as its owner
-- role; idempotent.
ALTER TABLE studio_run_step ADD COLUMN IF NOT EXISTS last_turn_at TIMESTAMPTZ;

INSERT INTO studio_runtime_config
    (config_key, config_value, value_type, description, default_value, min_value, max_value, max_items)
VALUES
 ('session.idle-ttl-seconds', '1800', 'int', 'Silence after which a SESSION step (the code agent''s run) is closed as expired: no turn, no finish. The run then settles and takes no more turns.', '1800', 60, 86400, NULL)
ON CONFLICT (config_key) DO NOTHING;
