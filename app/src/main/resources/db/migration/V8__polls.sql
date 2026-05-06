-- v1-polish: polls (#93). Single-choice polls — one vote per user
-- per poll. Multi-choice + ranked-choice are deferable extensions
-- the schema doesn't preclude (poll_votes PK is the gating
-- constraint, change it later if needed).

CREATE TABLE polls (
  id          BIGSERIAL PRIMARY KEY,
  author_id   BIGINT NOT NULL REFERENCES users(id),
  question    TEXT NOT NULL,
  closed_at   TIMESTAMPTZ,                 -- null = still open
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_polls_open ON polls(created_at DESC) WHERE closed_at IS NULL;
CREATE INDEX idx_polls_created ON polls(created_at DESC);

CREATE TABLE poll_options (
  id        BIGSERIAL PRIMARY KEY,
  poll_id   BIGINT NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
  text      TEXT NOT NULL,
  position  INT NOT NULL,                  -- 1-based display order
  UNIQUE (poll_id, position)
);
CREATE INDEX idx_poll_options_poll ON poll_options(poll_id, position);

CREATE TABLE poll_votes (
  poll_id    BIGINT NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
  option_id  BIGINT NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
  user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  voted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- Single-choice: one vote per user per poll. Re-voting replaces
  -- via UPSERT on this composite PK.
  PRIMARY KEY (poll_id, user_id)
);
CREATE INDEX idx_poll_votes_option ON poll_votes(option_id);

-- New first-time achievement: posting your first poll. Catalogue row;
-- the AchievementAwardingService picks it up via FIRST_BY_TOPIC.
INSERT INTO achievements (slug, name, description) VALUES
  ('first-poll', 'Question Time', 'Posted your first poll.');
