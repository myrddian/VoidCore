-- =============================================================================
-- V16 — Message-board ACL defaults
--
-- Boards join chat rooms as first-class ACL resources. Out of the box every
-- authenticated user can read/post in the seeded boards, while SYSOP retains
-- manage rights. Dynamic roles can then override or extend that surface.
-- =============================================================================

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'message_base', b.id, 'manage', 'sysop', NULL
  FROM message_bases b
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'message_base', b.id, 'view', 'authenticated', NULL
  FROM message_bases b
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'message_base', b.id, 'post', 'authenticated', NULL
  FROM message_bases b
ON CONFLICT DO NOTHING;
