-- Run by Testcontainers as the postgres superuser before Flyway connects.
-- Mirrors what sql/init/01-init-roles.sh does in the deploy stack so the
-- migration sees the same schema-extension state as production.
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
