-- =============================================================================
-- V18 — One-liners wall ACL defaults
--
-- Brings the shared one-liners wall onto the system ACL substrate as a
-- first-class resource. The wall is global, so resource_id 1 is the fixed
-- singleton identifier.
-- =============================================================================

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
VALUES ('oneliner_wall', 1, 'manage', 'sysop', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
VALUES ('oneliner_wall', 1, 'view', 'authenticated', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
VALUES ('oneliner_wall', 1, 'post', 'authenticated', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'oneliner_wall', 1, 'manage', 'role', roles.id
  FROM roles
 WHERE roles.name = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'oneliner_wall', 1, 'manage', 'role', roles.id
  FROM roles
 WHERE roles.name = 'MODERATOR'
ON CONFLICT DO NOTHING;
