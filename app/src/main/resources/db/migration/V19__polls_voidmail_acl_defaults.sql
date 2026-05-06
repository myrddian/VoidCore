-- =============================================================================
-- V19 — Poll and VoidMail ACL defaults
--
-- Polls become first-class ACL-backed assets. resource_id=0 is the global
-- poll hub (list/create). Individual poll ids carry view/post/manage grants.
-- VoidMail is a global subsystem gate with private mailbox ownership inside it.
-- =============================================================================

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
VALUES ('voidmail_system', 1, 'manage', 'sysop', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
VALUES ('voidmail_system', 1, 'view', 'authenticated', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
VALUES ('voidmail_system', 1, 'post', 'authenticated', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'voidmail_system', 1, 'manage', 'role', roles.id
  FROM roles
 WHERE roles.name = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
VALUES ('poll', 0, 'manage', 'sysop', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
VALUES ('poll', 0, 'view', 'authenticated', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
VALUES ('poll', 0, 'post', 'authenticated', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'poll', 0, 'manage', 'role', roles.id
  FROM roles
 WHERE roles.name IN ('ADMIN', 'MODERATOR')
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'poll', p.id, 'manage', 'sysop', NULL
  FROM polls p
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'poll', p.id, 'manage', 'user', p.author_id
  FROM polls p
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'poll', p.id, 'view', 'authenticated', NULL
  FROM polls p
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'poll', p.id, 'post', 'authenticated', NULL
  FROM polls p
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'poll', p.id, 'manage', 'role', roles.id
  FROM polls p
 CROSS JOIN roles
 WHERE roles.name IN ('ADMIN', 'MODERATOR')
ON CONFLICT DO NOTHING;
