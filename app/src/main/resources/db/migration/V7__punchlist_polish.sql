-- v1 punch-list polish bundle (#86 / #87 / #88 / #89 / #91 / #92 / #93).
-- Single migration adds all the supporting tables; the application code
-- in this PR uses scoped-down feature surfaces, but the schema is full.
-- Future polish PRs (e.g. moderation queue UI for #92) light up the
-- remaining surfaces against these tables without further migrations.

-- =====================================================================
-- #87  Recent activity feed (rolling event log)
-- =====================================================================
-- Bus events get persisted here so a "what happened recently" view
-- can scan a single table instead of correlating across primitives.
-- Cleanup: rows older than N days reaped by a scheduled task (out of
-- scope for v1, but the index supports future TTL pruning).
CREATE TABLE activity_events (
  id          BIGSERIAL PRIMARY KEY,
  topic       TEXT NOT NULL,                 -- bus topic that fired
  actor_id    BIGINT REFERENCES users(id),    -- user who triggered (null for system)
  payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
  emitted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_activity_events_emitted ON activity_events(emitted_at DESC);
CREATE INDEX idx_activity_events_actor   ON activity_events(actor_id, emitted_at DESC);

-- =====================================================================
-- #88  Reactions on content (polymorphic over kind)
-- =====================================================================
-- Single table covers every kind of content; (target_type, target_id)
-- is the polymorphic FK. App layer enforces the target_type vocabulary
-- (one of: bulletin, file, post, oneliner, document); no DB-level FK
-- because each type has a different parent table.
CREATE TABLE reactions (
  id            BIGSERIAL PRIMARY KEY,
  target_type   TEXT NOT NULL,
  target_id     BIGINT NOT NULL,
  user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reaction      TEXT NOT NULL,                  -- e.g. '+1', 'heart', 'fire'
  reacted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (target_type, target_id, user_id, reaction)
);
CREATE INDEX idx_reactions_target ON reactions(target_type, target_id);
CREATE INDEX idx_reactions_user   ON reactions(user_id);

-- =====================================================================
-- #89  Achievements + sysop notes
-- =====================================================================
-- Achievements are seeded by sysops; awarding is application-driven
-- (a service watches bus events for milestone triggers). Sysop notes
-- attach to a user as a private annotation visible to sysops only.
CREATE TABLE achievements (
  id          BIGSERIAL PRIMARY KEY,
  slug        TEXT NOT NULL UNIQUE,           -- e.g. 'first-thread'
  name        TEXT NOT NULL,                  -- displayable name
  description TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE user_achievements (
  user_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  achievement_id BIGINT NOT NULL REFERENCES achievements(id) ON DELETE CASCADE,
  awarded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, achievement_id)
);
CREATE INDEX idx_user_achievements_user ON user_achievements(user_id, awarded_at DESC);

CREATE TABLE sysop_notes (
  id          BIGSERIAL PRIMARY KEY,
  subject_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,  -- user the note is about
  author_id   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,  -- the sysop who wrote it
  body        TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sysop_notes_subject ON sysop_notes(subject_id, created_at DESC);

-- Seed the initial achievement catalogue. Awarded at runtime by the
-- AchievementsService when the bus reports the relevant milestone.
INSERT INTO achievements (slug, name, description) VALUES
  ('first-login',    'First Call',       'Logged in for the first time.'),
  ('first-thread',   'First Voice',      'Started a forum thread.'),
  ('first-post',     'First Reply',      'Replied to a forum thread.'),
  ('first-oneliner', 'On the Wall',      'Posted a one-liner.'),
  ('first-netmail',  'Personal Touch',   'Sent your first netmail.'),
  ('first-document', 'Author',           'Authored a document.'),
  ('caller-10',      'Regular',          'Called 10 times.'),
  ('caller-100',     'Veteran',          'Called 100 times.');

-- =====================================================================
-- #91  Watch list / follow another user
-- =====================================================================
-- Symmetric per-user bookmark of other users. The login summary
-- could highlight activity from watched users specifically (out of
-- scope for v1 of this PR — the table supports it).
CREATE TABLE watch_list (
  watcher_id   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  watched_id   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  watched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (watcher_id, watched_id),
  CHECK (watcher_id <> watched_id)               -- can't watch yourself
);
CREATE INDEX idx_watch_list_watched ON watch_list(watched_id);

-- =====================================================================
-- #92  Moderation queue (audit log already exists in sysop_actions)
-- =====================================================================
-- First-time post gates: new users' first oneliner / thread / netmail
-- land here pending sysop approval if the per-kind gate flag is on.
-- Default off; sysops opt in if/when spam shows up.
CREATE TABLE pending_content (
  id           BIGSERIAL PRIMARY KEY,
  kind         TEXT NOT NULL,                  -- 'oneliner' | 'thread' | 'netmail'
  author_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  payload      JSONB NOT NULL,                 -- kind-specific shape, deserialised on approve
  submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_by   BIGINT REFERENCES users(id),    -- null while pending
  decided_at   TIMESTAMPTZ,                    -- null while pending
  decision     TEXT                            -- null | 'approved' | 'rejected'
);
CREATE INDEX idx_pending_content_pending ON pending_content(submitted_at) WHERE decision IS NULL;

-- =====================================================================
-- #93  Atmosphere bundle: fortunes
-- =====================================================================
-- Tiny one-line snippets surfaced in various places (login banner,
-- goodbye screen). Sysop curates via direct SQL or future settings UI.
CREATE TABLE fortunes (
  id    BIGSERIAL PRIMARY KEY,
  text  TEXT NOT NULL
);
INSERT INTO fortunes (text) VALUES
  ('the future is rewritable; press [N] to overwrite it.'),
  ('signal in noise. carrier in static. user in the network.'),
  ('"every line of NFO is a love letter to a stranger." — anonymous'),
  ('the BBS is older than the web. that''s a feature.'),
  ('you are exactly where you need to be. type [I] to find out where.'),
  ('node 02 dreaming of node 03. the federation begins one socket at a time.'),
  ('structure outlasts spectacle.'),
  ('a linkifier is just a wiki dressed in a phosphor coat.'),
  ('14400 baud carries more than its bandwidth budget.');
