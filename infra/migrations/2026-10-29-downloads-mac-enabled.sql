-- orazaka-web-client#353: the Mac download switch is a setting the admin flips in the console (audited), read without a token
-- by the public site. Applied once to an existing orazaka_studio_db, as its owner role. Idempotent; never overwrites an admin's value.
INSERT INTO studio_runtime_config
    (config_key, config_value, value_type, description, default_value, min_value, max_value, max_items)
VALUES
 ('downloads.mac-enabled', 'false', 'boolean', 'Hands out the Mac build on the public download page. Off while the build is not distributed (public, read without a token).', 'false', NULL, NULL, NULL)
ON CONFLICT (config_key) DO NOTHING;
