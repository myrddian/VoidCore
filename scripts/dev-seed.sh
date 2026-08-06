#!/usr/bin/env bash
#
# Populate the local development database with enough content to exercise
# the UI: message boards, their ACL grants, and a wall of one-liners.
#
# Why this is a script and not a migration: the engine ships EMPTY on
# purpose. FlywayMigrationIntegrationTest asserts `message_bases` starts
# at zero rows, and docs/extending-voidcore.md puts reference-instance
# seed data in the operator's overlay repo, not in core. This gives a
# developer a usable board without changing what a fresh clone ships.
#
# LOCAL DEVELOPMENT ONLY. Refuses to run against anything but a local
# container.
set -euo pipefail

CONTAINER="${VOIDCORE_DEV_PG_CONTAINER:-voidcore-dev-pg}"

if ! docker ps --format '{{.Names}}' | grep -qx "${CONTAINER}"; then
  echo "error: container '${CONTAINER}' is not running — start it with scripts/dev-db.sh" >&2
  exit 1
fi

psql() { docker exec -i "${CONTAINER}" psql -U postgres -d voidcore -v ON_ERROR_STOP=1 -tAq "$@"; }

echo "seeding message boards…"
psql <<'SQL'
INSERT INTO message_bases (slug, name, description, sort_order) VALUES
  ('general',  'General',            'Anything goes within reason.',      10),
  ('support',  'Help & Support',     'Questions, answers, troubleshooting.', 20),
  ('showcase', 'Showcase',           'Show what you have been working on.',  30),
  ('meta',     'Meta',               'Feedback about the board itself.',     40)
ON CONFLICT (slug) DO NOTHING;
SQL

# Boards are invisible without ACL grants — a base with no grant fails the
# VIEW check and simply doesn't appear. V16 grants these at migration time
# for rows that exist then; rows inserted later need the same treatment.
echo "granting board ACLs…"
psql <<'SQL'
INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'message_base', b.id, p, 'authenticated', NULL
  FROM message_bases b, (VALUES ('view'), ('post')) AS perms(p)
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'message_base', b.id, 'manage', 'sysop', NULL
  FROM message_bases b
ON CONFLICT DO NOTHING;
SQL

echo "seeding one-liners…"
psql <<'SQL'
INSERT INTO oneliners (author_id, body, posted_at)
SELECT (SELECT id FROM users ORDER BY id LIMIT 1),
       'dev seed line ' || g,
       now() - (g || ' minutes')::interval
FROM generate_series(1, 45) g
WHERE EXISTS (SELECT 1 FROM users);
SQL

printf 'done — boards: %s, grants: %s, one-liners: %s\n' \
  "$(psql -c 'SELECT count(*) FROM message_bases;')" \
  "$(psql -c "SELECT count(*) FROM acl_grants WHERE resource_type='message_base';")" \
  "$(psql -c 'SELECT count(*) FROM oneliners;')"
