-- =============================================================================
-- V5 — File catalog metadata (year / artist / label / catalog_number / genre).
--
-- Up to V4 a `files` row had filename, title, NFO body, and an external URL.
-- That covers "find the release on Bandcamp" but loses the rest of the
-- metadata that NFO releases traditionally carry (year, artist, label,
-- catalog number, genre). Sysop edit flow only knew how to update the URL,
-- which is what prompted this migration.
--
-- All columns are nullable so existing rows keep working without backfill.
-- The sysop edit screen (post-V5 ScreenRouter) lets the operator fill these
-- in per-row.
-- =============================================================================

ALTER TABLE files
  ADD COLUMN year            SMALLINT,
  ADD COLUMN artist          TEXT,
  ADD COLUMN label           TEXT,
  ADD COLUMN catalog_number  TEXT,
  ADD COLUMN genre           TEXT;

-- Sanity: 1900 <= year <= now+1 (allow next-year pre-announcements).
-- Soft check; UI also validates. NULL passes.
ALTER TABLE files
  ADD CONSTRAINT files_year_sane
  CHECK (year IS NULL OR (year BETWEEN 1900 AND 2100));
