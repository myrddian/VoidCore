-- =============================================================================
-- V17 — Default role grants for seeded assets
--
-- Makes the built-in ADMIN and MODERATOR roles usable immediately.
-- SYSOP remains the root/owner path; these are delegated defaults.
-- =============================================================================

-- ADMIN: broad delegated control across all current ACL-backed assets.
INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'chat_room', r.id, 'manage', 'role', roles.id
  FROM chat_rooms r
  CROSS JOIN roles
 WHERE roles.name = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'message_base', b.id, 'manage', 'role', roles.id
  FROM message_bases b
  CROSS JOIN roles
 WHERE roles.name = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'document', d.id, 'manage', 'role', roles.id
  FROM documents d
  CROSS JOIN roles
 WHERE roles.name = 'ADMIN'
   AND d.deleted_at IS NULL
ON CONFLICT DO NOTHING;

-- MODERATOR: community-space control plus announcement editing.
INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'chat_room', r.id, 'manage', 'role', roles.id
  FROM chat_rooms r
  CROSS JOIN roles
 WHERE roles.name = 'MODERATOR'
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'message_base', b.id, 'manage', 'role', roles.id
  FROM message_bases b
  CROSS JOIN roles
 WHERE roles.name = 'MODERATOR'
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'document', d.id, 'view', 'role', roles.id
  FROM documents d
  CROSS JOIN roles
 WHERE roles.name = 'MODERATOR'
   AND d.type_slug = 'article'
   AND d.deleted_at IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'document', d.id, 'edit', 'role', roles.id
  FROM documents d
  CROSS JOIN roles
 WHERE roles.name = 'MODERATOR'
   AND d.type_slug = 'article'
   AND d.deleted_at IS NULL
ON CONFLICT DO NOTHING;
