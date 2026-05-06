-- =============================================================================
-- V15 — Dynamic roles and role-aware ACL principals
--
-- SYSOP remains the hard owner/superuser path via users.is_sysop.
-- All other delegated authority is modelled as named roles assigned
-- to otherwise-normal USER accounts.
-- =============================================================================

CREATE TABLE roles (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name          TEXT NOT NULL UNIQUE,
  description   TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
  user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role_id       BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
  granted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_role
  ON user_roles (role_id, user_id);

INSERT INTO roles (name, description)
VALUES
  ('ADMIN', 'Default seeded admin role for broad delegated management'),
  ('MODERATOR', 'Default seeded moderator role for scoped moderation duties')
ON CONFLICT (name) DO NOTHING;

ALTER TABLE acl_grants DROP CONSTRAINT chk_acl_principal_type;
ALTER TABLE acl_grants DROP CONSTRAINT chk_acl_principal_id;

ALTER TABLE acl_grants
  ADD CONSTRAINT chk_acl_principal_type
  CHECK (principal_type IN ('everyone', 'authenticated', 'user', 'role', 'sysop'));

ALTER TABLE acl_grants
  ADD CONSTRAINT chk_acl_principal_id
  CHECK (
    (principal_type IN ('user', 'role') AND principal_id IS NOT NULL)
    OR
    (principal_type NOT IN ('user', 'role') AND principal_id IS NULL)
  );
