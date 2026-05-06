-- =============================================================================
-- V23 — achievement metadata
-- =============================================================================
-- Adds first-class columns for the door-sourced achievement protocol so the
-- Achievements screen can render points and group by category, and so an
-- operator can tell at a glance whether an achievement is BBS-native or
-- came in from a sidecar door.
--
--   points    INTEGER  — door's declared score for the unlock; 0 for legacy.
--   category  TEXT     — free-form grouping ("combat", "lore", "social",
--                        "shadow" for hidden cards, "story", etc).
--   source    TEXT     — 'bbs' for native, 'door' for door-sourced. The
--                        original V7 catalogue rows are all 'bbs'.
--
-- The door protocol stays unchanged on the wire; this migration just lets
-- the BBS persist the metadata the protocol was already sending.
-- =============================================================================

ALTER TABLE achievements
  ADD COLUMN IF NOT EXISTS points    INTEGER     NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS category  TEXT        NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS source    TEXT        NOT NULL DEFAULT 'bbs';

-- The V7 catalogue rows pre-date the door protocol. Tag them explicitly so
-- the source column reads true for everything, not just future inserts.
UPDATE achievements
  SET source = 'bbs'
  WHERE source IS NULL OR source = '';

-- Light category hints for the seeded BBS-native catalogue (these can be
-- adjusted later without another migration since updates flow through the
-- repo).
UPDATE achievements SET category = 'milestone' WHERE slug = 'first-login';
UPDATE achievements SET category = 'social'    WHERE slug IN ('first-thread', 'first-post', 'first-oneliner', 'first-netmail');
UPDATE achievements SET category = 'creation'  WHERE slug IN ('first-document', 'first-poll');
UPDATE achievements SET category = 'milestone' WHERE slug IN ('caller-10', 'caller-100');

-- Reasonable point values for the BBS-native catalogue so its score isn't
-- entirely zero next to door achievements that have explicit values.
UPDATE achievements SET points =  5 WHERE slug = 'first-login';
UPDATE achievements SET points =  5 WHERE slug = 'first-thread';
UPDATE achievements SET points =  5 WHERE slug = 'first-post';
UPDATE achievements SET points =  5 WHERE slug = 'first-oneliner';
UPDATE achievements SET points =  5 WHERE slug = 'first-netmail';
UPDATE achievements SET points = 10 WHERE slug = 'first-document';
UPDATE achievements SET points = 10 WHERE slug = 'first-poll';
UPDATE achievements SET points = 10 WHERE slug = 'caller-10';
UPDATE achievements SET points = 25 WHERE slug = 'caller-100';

CREATE INDEX IF NOT EXISTS idx_achievements_source ON achievements(source);
