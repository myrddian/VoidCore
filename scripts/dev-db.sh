#!/usr/bin/env bash
#
# Start (or restart) a local Postgres for development.
#
# Uses the same image and the same sql/init role bootstrap as the Compose
# stack, but publishes 5432 so an app running on the host can reach it —
# the Compose postgres service deliberately stays on the private network.
#
# Local development defaults only. Not for anything real.
set -euo pipefail

cd "$(dirname "$0")/.."

CONTAINER="${VOIDCORE_DEV_PG_CONTAINER:-voidcore-dev-pg}"
PASSWORD="${VOIDCORE_DEV_PG_PASSWORD:-devlocal}"

if [ "$(docker ps -aq -f name="^${CONTAINER}$")" ]; then
  echo "starting existing container ${CONTAINER}"
  docker start "${CONTAINER}" >/dev/null
else
  echo "creating container ${CONTAINER}"
  docker run -d --name "${CONTAINER}" -p 5432:5432 \
    -e POSTGRES_USER=postgres \
    -e POSTGRES_PASSWORD="${PASSWORD}" \
    -e POSTGRES_DB=voidcore \
    -e VOIDCORE_APP_USER=voidcore_app \
    -e VOIDCORE_APP_PASSWORD="${PASSWORD}" \
    -v "$(pwd)/sql/init:/docker-entrypoint-initdb.d:ro" \
    postgres:17-alpine >/dev/null
fi

printf 'waiting for postgres'
for _ in $(seq 1 30); do
  if docker exec "${CONTAINER}" pg_isready -U postgres -d voidcore >/dev/null 2>&1; then
    echo " ready"
    exit 0
  fi
  printf '.'
  sleep 1
done

echo " timed out" >&2
exit 1
