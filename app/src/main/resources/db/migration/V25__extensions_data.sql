CREATE TABLE IF NOT EXISTS extensions_data (
    extension_slug TEXT        NOT NULL,
    scope_type     TEXT        NOT NULL,
    scope_key      TEXT        NOT NULL DEFAULT '',
    key            TEXT        NOT NULL,
    value          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (extension_slug, scope_type, scope_key, key)
);

CREATE INDEX IF NOT EXISTS extensions_data_scan
    ON extensions_data (extension_slug, scope_type, scope_key, key text_pattern_ops);
