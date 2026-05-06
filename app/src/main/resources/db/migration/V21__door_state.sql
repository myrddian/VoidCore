CREATE TABLE IF NOT EXISTS door_state (
    door_id     TEXT NOT NULL,
    scope       TEXT NOT NULL CHECK (scope IN ('user', 'shared', 'global')),
    scope_key   TEXT NOT NULL,
    key         TEXT NOT NULL,
    value       JSONB NOT NULL,
    version     BIGINT NOT NULL DEFAULT 1,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (door_id, scope, scope_key, key)
);

CREATE INDEX IF NOT EXISTS door_state_scan
    ON door_state (door_id, scope, scope_key, key text_pattern_ops);
