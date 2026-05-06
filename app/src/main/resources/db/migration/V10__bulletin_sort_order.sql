-- =============================================================================
-- V10 — Explicit bulletin ordering
--
-- Sysop bulletin management now supports manual reordering. Preserve the
-- legacy display order (pinned first, then newest) by backfilling sort_order
-- from the current rows, then let the app swap neighbouring values.
-- =============================================================================

ALTER TABLE bulletins ADD COLUMN sort_order INTEGER;

WITH ranked AS (
  SELECT id,
         row_number() OVER (
           ORDER BY pinned DESC, posted_at DESC, id ASC
         ) AS rn
  FROM bulletins
)
UPDATE bulletins b
SET sort_order = ranked.rn
FROM ranked
WHERE b.id = ranked.id;

ALTER TABLE bulletins
  ALTER COLUMN sort_order SET NOT NULL;

ALTER TABLE bulletins
  ALTER COLUMN sort_order SET DEFAULT 0;

CREATE INDEX idx_bulletins_display
  ON bulletins (pinned DESC, sort_order ASC, posted_at DESC);
