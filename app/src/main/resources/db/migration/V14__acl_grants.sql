-- =============================================================================
-- V14 — Generic ACL grants substrate
--
-- System-wide permission model for user-facing resources. Chat rooms adopt it
-- first; documents/releases can migrate onto the same substrate later.
-- =============================================================================

CREATE TABLE acl_grants (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  resource_type   TEXT NOT NULL,
  resource_id     BIGINT NOT NULL,
  permission      TEXT NOT NULL,
  principal_type  TEXT NOT NULL,
  principal_id    BIGINT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT chk_acl_permission
    CHECK (permission IN ('view', 'post', 'edit', 'manage')),
  CONSTRAINT chk_acl_principal_type
    CHECK (principal_type IN ('everyone', 'authenticated', 'user', 'sysop')),
  CONSTRAINT chk_acl_principal_id
    CHECK (
      (principal_type = 'user' AND principal_id IS NOT NULL)
      OR
      (principal_type <> 'user' AND principal_id IS NULL)
    ),
  CONSTRAINT uq_acl_grants
    UNIQUE (resource_type, resource_id, permission, principal_type, principal_id)
);

CREATE INDEX idx_acl_grants_resource
  ON acl_grants (resource_type, resource_id, permission);

CREATE INDEX idx_acl_grants_principal
  ON acl_grants (principal_type, principal_id, permission);

-- Chat rooms adopt the ACL system first:
-- public rooms => any authenticated user may view/post
-- private rooms => explicit membership rows define view/post access
-- sysops => manage every room

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'chat_room', r.id, 'manage', 'sysop', NULL
  FROM chat_rooms r
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'chat_room', r.id, 'view', 'authenticated', NULL
  FROM chat_rooms r
 WHERE r.is_private = false
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'chat_room', r.id, 'post', 'authenticated', NULL
  FROM chat_rooms r
 WHERE r.is_private = false
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'chat_room', m.room_id, 'view', 'user', m.user_id
  FROM chat_room_members m
ON CONFLICT DO NOTHING;

INSERT INTO acl_grants (resource_type, resource_id, permission, principal_type, principal_id)
SELECT 'chat_room', m.room_id, 'post', 'user', m.user_id
  FROM chat_room_members m
ON CONFLICT DO NOTHING;
