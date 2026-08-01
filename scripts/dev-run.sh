#!/usr/bin/env bash
#
# Run VOIDcore against a local Postgres, without the Docker app image.
#
# Faster loop than `docker compose up --build`: Gradle rebuilds in place
# and the frontend bundle is rebuilt by the same task, so a code change is
# one restart away instead of an image build away.
#
# Expects Postgres on localhost:5432 with the voidcore_app role — see
# scripts/dev-db.sh, which starts one matching sql/init.
#
# Every value below is a LOCAL DEVELOPMENT DEFAULT and is overridable from
# the environment. Do not reuse these anywhere real.
set -euo pipefail

cd "$(dirname "$0")/../app"

# 8080 is often taken on a dev box (Docker Desktop binds it, among others),
# so the dev runner defaults to 8090. Override with SERVER_PORT.
export SERVER_PORT="${SERVER_PORT:-8090}"

export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:5432/voidcore}"
export SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-voidcore_app}"
export SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-devlocal}"

# Bootstraps a sysop on first boot so the sysop screens are reachable.
#
# NOTE the names: the app reads VOIDCORE_SYSOP_*. The SYSOP_* names used in
# README/.env.example are Compose-level, mapped across by docker-compose.yml —
# bypassing Compose means using the app's own names or the bootstrap silently
# skips. Set a password before first boot to get a sysop account; left blank,
# the first user you register is just a regular member.
export VOIDCORE_SYSOP_HANDLE="${VOIDCORE_SYSOP_HANDLE:-sysop}"
export VOIDCORE_SYSOP_INITIAL_PASSWORD="${VOIDCORE_SYSOP_INITIAL_PASSWORD:-}"

# Exercise the overlay seams (themes, skins, manifest-backed screens)
# against the worked example that ships with the repo.
export VOIDCORE_INSTANCE_ROOT="${VOIDCORE_INSTANCE_ROOT:-$(pwd)/dev-instance}"

exec ./gradlew bootRun --console=plain
