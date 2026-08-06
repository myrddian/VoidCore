#!/usr/bin/env bash
#
# Mint a session token for an existing user in the local development
# database, so a browser can resume straight into an authenticated screen
# without going through the login flow by hand.
#
# Useful when verifying a screen that lives behind auth — the alternative
# is retyping credentials after every server restart.
#
#   ./scripts/dev-session.sh test
#   # then, in the browser console:
#   localStorage.setItem("voidcore:session", "<printed token>"); location.reload()
#
# LOCAL DEVELOPMENT ONLY. This bypasses authentication by construction, so
# it refuses to run against anything but the local dev container, and it
# will not create users — the account has to exist already.
set -euo pipefail

HANDLE="${1:-}"
CONTAINER="${VOIDCORE_DEV_PG_CONTAINER:-voidcore-dev-pg}"

if [ -z "${HANDLE}" ]; then
  echo "usage: $(basename "$0") <handle>" >&2
  exit 64
fi

if ! docker ps --format '{{.Names}}' | grep -qx "${CONTAINER}"; then
  echo "error: container '${CONTAINER}' is not running — this script only" >&2
  echo "       targets the local dev database (scripts/dev-db.sh)." >&2
  exit 1
fi

psql() { docker exec -i "${CONTAINER}" psql -U postgres -d voidcore -v ON_ERROR_STOP=1 -tAq "$@"; }

if [ "$(psql -c "SELECT count(*) FROM users WHERE handle = '${HANDLE}';")" != "1" ]; then
  echo "error: no user '${HANDLE}'. Register through the BBS first — this" >&2
  echo "       script deliberately does not create accounts." >&2
  exit 1
fi

TOKEN="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"

# One live session per run keeps the reattach path (ADR-033) simple: a
# stale actor being swapped out reads as a close to the client, which then
# clears its token and bounces to the login screen.
psql <<SQL >/dev/null
DELETE FROM sessions WHERE user_id = (SELECT id FROM users WHERE handle = '${HANDLE}');
INSERT INTO sessions (token, user_id, expires_at, ip, ua, current_screen)
SELECT '${TOKEN}', id, now() + interval '1 day', '127.0.0.1', 'dev-session/1.0',
       '{"kind":"menu"}'::jsonb
  FROM users WHERE handle = '${HANDLE}';
SQL

echo "${TOKEN}"
echo >&2
echo "In the browser console at http://localhost:8090 :" >&2
echo "  localStorage.setItem(\"voidcore:session\", \"${TOKEN}\"); location.reload()" >&2
