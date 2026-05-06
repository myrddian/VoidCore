-- =============================================================================
-- V1 — Initial schema for VOIDcore
--
-- Mirrors SPEC.md §3 verbatim, with one correction: SPEC §3 shows the handle
-- format check as `CREATE CONSTRAINT TRIGGER ... CHECK (...)`, which is not
-- valid Postgres syntax (CONSTRAINT TRIGGER takes a function reference, not a
-- predicate). Implemented here as a regular table CHECK constraint.
-- =============================================================================

-- Extensions -------------------------------------------------------------------
-- Created by sql/init/01-init-roles.sh in the deploy artifact, running as
-- the postgres superuser before Flyway connects as voidcore_app. Listed here as
-- documentation; Flyway running as voidcore_app cannot CREATE EXTENSION. Tests
-- using Testcontainers create them via withInitScript().
-- CREATE EXTENSION IF NOT EXISTS citext;
-- CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Users ------------------------------------------------------------------------
CREATE TABLE users (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  handle          CITEXT UNIQUE NOT NULL
                  CHECK (handle ~ '^[A-Za-z0-9_\-.]{3,16}$'),
  pw_hash         TEXT NOT NULL,
  location        TEXT,
  setup           TEXT,
  found_via       TEXT,
  fav_genres      TEXT,
  bio             TEXT,
  preferences     JSONB NOT NULL DEFAULT '{}'::jsonb,
  joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_call_at    TIMESTAMPTZ,
  call_count      INTEGER NOT NULL DEFAULT 0,
  post_count      INTEGER NOT NULL DEFAULT 0,
  is_sysop        BOOLEAN NOT NULL DEFAULT false,
  is_banned       BOOLEAN NOT NULL DEFAULT false,
  banned_reason   TEXT
);
CREATE INDEX idx_users_lastcall ON users(last_call_at DESC);

-- Sessions (server-side, opaque tokens) ---------------------------------------
CREATE TABLE sessions (
  token           TEXT PRIMARY KEY,                -- 32 bytes hex
  user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at      TIMESTAMPTZ NOT NULL,
  ip              INET,
  ua              TEXT,
  -- Tracks where the user is in the BBS so reconnects (and server restarts)
  -- land them in the right area instead of bouncing them to the connect
  -- screen. Updated on every navigation.
  current_screen  JSONB NOT NULL DEFAULT '{"kind":"menu"}'::jsonb
);
CREATE INDEX idx_sessions_user    ON sessions(user_id);
CREATE INDEX idx_sessions_expires ON sessions(expires_at);

-- Login attempt log (for rate limiting) ---------------------------------------
CREATE TABLE login_attempts (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ip              INET NOT NULL,
  handle          CITEXT,
  success         BOOLEAN NOT NULL,
  at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_logattempts_ip_at ON login_attempts(ip, at);

-- Bulletins (sysop-authored, read-only for users) -----------------------------
CREATE TABLE bulletins (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title           TEXT NOT NULL,
  body            TEXT NOT NULL,
  posted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  pinned          BOOLEAN NOT NULL DEFAULT false
);

-- File area (releases). Files are catalog entries; actual audio is hosted
-- elsewhere (Bandcamp/Soundcloud) and linked.
CREATE TABLE files (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  filename        TEXT NOT NULL,
  title           TEXT NOT NULL,
  size_bytes      BIGINT NOT NULL,
  uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  uploader_id     BIGINT REFERENCES users(id),
  download_count  INTEGER NOT NULL DEFAULT 0,
  nfo_text        TEXT NOT NULL,
  external_url    TEXT,
  area            TEXT NOT NULL DEFAULT 'releases'
);
CREATE INDEX idx_files_area ON files(area, uploaded_at DESC);

-- Message bases ("conferences" in BBS speak) ----------------------------------
CREATE TABLE message_bases (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug            TEXT UNIQUE NOT NULL,
  name            TEXT NOT NULL,
  description     TEXT,
  sort_order      INTEGER NOT NULL DEFAULT 0,
  is_locked       BOOLEAN NOT NULL DEFAULT false
);

-- Threads ---------------------------------------------------------------------
CREATE TABLE threads (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  base_id         BIGINT NOT NULL REFERENCES message_bases(id) ON DELETE CASCADE,
  subject         TEXT NOT NULL,
  author_id       BIGINT NOT NULL REFERENCES users(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_post_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  post_count      INTEGER NOT NULL DEFAULT 0,
  is_pinned       BOOLEAN NOT NULL DEFAULT false,
  is_locked       BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_threads_base_lastpost ON threads(base_id, last_post_at DESC);

-- Posts -----------------------------------------------------------------------
CREATE TABLE posts (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  thread_id       BIGINT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
  author_id       BIGINT NOT NULL REFERENCES users(id),
  body            TEXT NOT NULL,
  posted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  edited_at       TIMESTAMPTZ,
  is_deleted      BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_posts_thread ON posts(thread_id, posted_at);

-- Read state per user per thread (for unread counts) -------------------------
CREATE TABLE thread_read (
  user_id         BIGINT NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
  thread_id       BIGINT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
  last_read_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, thread_id)
);

-- Chat history (multinode chat — single global room for v1) -----------------
CREATE TABLE chat_messages (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  author_id       BIGINT NOT NULL REFERENCES users(id),
  body            TEXT NOT NULL,
  kind            TEXT NOT NULL DEFAULT 'msg',     -- msg | action | system
  posted_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_posted ON chat_messages(posted_at DESC);

-- One-liner wall --------------------------------------------------------------
CREATE TABLE oneliners (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  author_id       BIGINT NOT NULL REFERENCES users(id),
  body            TEXT NOT NULL CHECK (length(body) <= 70),
  posted_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_oneliners_posted ON oneliners(posted_at DESC);

-- NetMail (private messages between users) ------------------------------------
CREATE TABLE netmail (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  from_id         BIGINT NOT NULL REFERENCES users(id),
  to_id           BIGINT NOT NULL REFERENCES users(id),
  subject         TEXT NOT NULL,
  body            TEXT NOT NULL,
  sent_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  read_at         TIMESTAMPTZ,
  from_deleted    BOOLEAN NOT NULL DEFAULT false,
  to_deleted      BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_netmail_to   ON netmail(to_id,   sent_at DESC);
CREATE INDEX idx_netmail_from ON netmail(from_id, sent_at DESC);

-- Last-callers (denormalised cache, capped via app-side housekeeping) --------
CREATE TABLE last_callers (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lastcallers_at ON last_callers(at DESC);

-- Site-wide counters ----------------------------------------------------------
CREATE TABLE counters (
  key             TEXT PRIMARY KEY,
  value           BIGINT NOT NULL DEFAULT 0
);

-- Sysop audit log -------------------------------------------------------------
CREATE TABLE sysop_actions (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_id        BIGINT NOT NULL REFERENCES users(id),
  action          TEXT NOT NULL,
  payload         JSONB,
  at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sysop_actions_at ON sysop_actions(at DESC);
