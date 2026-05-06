#!/usr/bin/env bash
# =============================================================================
# Regenerate the jOOQ classes under app/src/jooq/java/ from the current
# Flyway migrations. Run this AFTER editing any V*.sql migration.
#
# What it does, per ADR-005a:
#   1. Spin up a throwaway Postgres 17 container
#   2. Apply our migrations (V1, V2, V3, …)
#   3. Run jOOQ codegen against the resulting schema
#   4. Tear down the container
#
# The generated classes are committed to git. SPEC §14's target is
# Testcontainers-driven codegen on every Gradle build; that's blocked on
# Docker Desktop hardening that prevents Testcontainers from connecting in
# this dev environment. Switch to the Gradle-task version of this when that
# clears (issue: parameter-of-time tracker).
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

CONTAINER=voidcore-pg-jooq
PORT=55440

echo "==> starting throwaway postgres on port $PORT"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run --rm -d --name "$CONTAINER" \
  -e POSTGRES_PASSWORD=devpw -e POSTGRES_DB=voidcore \
  -p "$PORT:5432" postgres:17-alpine >/dev/null

trap 'docker rm -f "$CONTAINER" >/dev/null 2>&1 || true' EXIT

echo "==> waiting for postgres to be ready"
for i in $(seq 1 30); do
  if docker exec "$CONTAINER" pg_isready -U postgres -d voidcore 2>/dev/null | grep -q accepting; then
    echo "    ready"
    break
  fi
  sleep 1
done

echo "==> creating extensions (mirrors prod sql/init)"
docker exec -i "$CONTAINER" psql -U postgres -d voidcore -q -v ON_ERROR_STOP=1 \
  < app/src/test/resources/init-extensions.sql >/dev/null

echo "==> applying Flyway migrations"
while IFS= read -r v; do
  echo "    $(basename "$v")"
  docker exec -i "$CONTAINER" psql -U postgres -d voidcore -q -v ON_ERROR_STOP=1 < "$v" >/dev/null
  # V6's backfill RAISEs if no sysop user exists. Seed one between V5 and V6
  # so jOOQ codegen against a bare DB (no SysopBootstrap) still works.
  # Production deploys run SysopBootstrap before Flyway so they don't need it.
  if [[ "$(basename "$v")" == "V5__file_metadata.sql" ]]; then
    echo "    [seed] stub sysop user for V6 backfill"
    docker exec -i "$CONTAINER" psql -U postgres -d voidcore -q -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
INSERT INTO users (handle, pw_hash, is_sysop) VALUES ('SYSOP', 'x', true)
ON CONFLICT (handle) DO UPDATE SET is_sysop = true;
SQL
  fi
done < <(find app/src/main/resources/db/migration -maxdepth 1 -name 'V*.sql' | sort -V)

echo "==> running jOOQ codegen"
rm -rf app/src/jooq/java/*
./app/gradlew --no-daemon -p app generateJooq

echo
echo "==> done. Generated tables:"
ls app/src/jooq/java/io/aeyer/voidcore/jooq/tables/ | grep '\.java$'
