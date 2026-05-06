-- =============================================================================
-- V6 — Documents substrate (ADR-023 / SPEC-documents.md §2.1).
--
-- Lands the `documents` table (one global pool, kind-typed, faceted-navigable),
-- its three siblings (`document_editors`, `document_links`,
-- `document_revisions`), and the tsvector trigger that maintains
-- `documents.search_vector` for full-text search (§8.1).
--
-- Backfills `files` rows into `documents` as `kind=release` (§3.5 / §10.1) and
-- `bulletins` rows as `kind=article` (§3.2 / §10.1). The old `files` and
-- `bulletins` tables stay intact — they are dropped by a future V-final
-- migration once every consumer (BulletinRepository, FileRepository, the
-- bulletin/file screens) has migrated to read from `documents` via
-- DocumentView.
--
-- Author backfill: `bulletins` has no `author_id` column, and
-- `files.uploader_id` is nullable. Both fall back to the first sysop user.
-- SysopBootstrap guarantees at least one sysop user exists at app start, so
-- the fallback is always populated; we RAISE EXCEPTION if it isn't.
-- =============================================================================

-- 1. Schema -------------------------------------------------------------------

CREATE TABLE documents (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug            TEXT NOT NULL UNIQUE,
  title           TEXT NOT NULL,
  kind            TEXT NOT NULL CHECK (kind IN
                    ('howto', 'article', 'link', 'glossary',
                     'release', 'note')),
  body            TEXT NOT NULL,
  frontmatter     JSONB NOT NULL DEFAULT '{}'::jsonb,
  tags            TEXT[] NOT NULL DEFAULT '{}'::text[],
  author_id       BIGINT NOT NULL REFERENCES users(id),
  visibility      TEXT NOT NULL DEFAULT 'public'
                    CHECK (visibility IN ('public', 'private')),
  status          TEXT NOT NULL DEFAULT 'published'
                    CHECK (status IN ('draft', 'pending', 'published')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  search_vector   tsvector,
  anchor_document_id UUID
);

CREATE INDEX documents_search   ON documents USING GIN (search_vector);
CREATE INDEX documents_tags     ON documents USING GIN (tags);
CREATE INDEX documents_kind     ON documents (kind);
CREATE INDEX documents_author   ON documents (author_id);
CREATE INDEX documents_status   ON documents (status);
CREATE INDEX documents_updated  ON documents (updated_at DESC);

CREATE TABLE document_editors (
  document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  user_id     BIGINT NOT NULL REFERENCES users(id),
  PRIMARY KEY (document_id, user_id)
);

CREATE TABLE document_links (
  source_id  BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  target_id  BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  kind       TEXT NOT NULL DEFAULT 'reference',
  PRIMARY KEY (source_id, target_id, kind)
);
CREATE INDEX document_links_target ON document_links (target_id);

CREATE TABLE document_revisions (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  body        TEXT NOT NULL,
  frontmatter JSONB NOT NULL,
  edited_by   BIGINT NOT NULL REFERENCES users(id),
  edited_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX document_revisions_doc ON document_revisions (document_id, edited_at DESC);

-- 2. Search vector trigger ----------------------------------------------------

-- `simple` config (no stemming) is deliberate per SPEC-documents.md §2.1 —
-- a music-vocab community shouldn't have "EBM" stemmed to "ebm".
CREATE OR REPLACE FUNCTION documents_update_search_vector()
RETURNS TRIGGER AS $$
BEGIN
  NEW.search_vector :=
    setweight(to_tsvector('simple', coalesce(NEW.title, '')),                                    'A') ||
    setweight(to_tsvector('simple', array_to_string(coalesce(NEW.tags, ARRAY[]::text[]), ' ')),  'B') ||
    setweight(to_tsvector('simple', coalesce(NEW.body, '')),                                     'C');
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER documents_search_vector
  BEFORE INSERT OR UPDATE OF title, tags, body
  ON documents FOR EACH ROW
  EXECUTE FUNCTION documents_update_search_vector();

-- 3. Backfill ----------------------------------------------------------------

-- 3a. Sysop fallback. Bulletins have no author_id; files.uploader_id may be
-- NULL. Both fall back to the first sysop user (ORDER BY id LIMIT 1).
-- The DO block validates one exists and RAISES early if not (the INSERTs
-- below would otherwise fail with a NOT NULL violation, but that's a less
-- helpful error message). The actual fallback id is recomputed by an inlined
-- subquery in each INSERT below so we don't depend on a temp table outliving
-- a statement boundary (psql auto-commits between statements when applying
-- the migration outside Flyway, e.g. for the jOOQ regen script).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM users WHERE is_sysop = true) THEN
    RAISE EXCEPTION 'V6 backfill requires at least one sysop user; '
                    'run SysopBootstrap or seed a sysop manually before '
                    'applying this migration.';
  END IF;
END $$;

-- 3b. Files → documents kind=release. Slug = lowercase(filename minus final
-- extension); collisions get a deterministic -2/-3 suffix (per SPEC §2.3).
-- ON CONFLICT DO NOTHING is a defensive net beyond the dedupe window — if the
-- algorithm misses a case, the test in DocumentsMigrationIntegrationTest will
-- detect a row-count mismatch.
INSERT INTO documents (slug, title, kind, body, frontmatter,
                       tags, author_id, visibility, status,
                       created_at, updated_at)
SELECT
  CASE
    WHEN dedupe_index = 1 THEN base_slug
    ELSE base_slug || '-' || dedupe_index
  END                                                AS slug,
  title,
  'release'                                          AS kind,
  coalesce(nfo_text, '')                             AS body,
  jsonb_build_object(
    'filename',       filename,
    'artist',         artist,
    'year',           year,
    'label',          label,
    'catalog_number', catalog_number,
    'genre',          genre,
    'external_url',   external_url,
    'size_bytes',     size_bytes,
    'download_count', download_count
  )                                                  AS frontmatter,
  ARRAY[]::text[]                                    AS tags,
  coalesce(uploader_id, (SELECT id FROM users WHERE is_sysop = true ORDER BY id LIMIT 1))
                                                     AS author_id,
  'public'                                           AS visibility,
  'published'                                        AS status,
  uploaded_at                                        AS created_at,
  uploaded_at                                        AS updated_at
FROM (
  SELECT
    f.*,
    lower(regexp_replace(filename, '\.[^.]+$', ''))               AS base_slug,
    row_number() OVER (
      PARTITION BY lower(regexp_replace(filename, '\.[^.]+$', ''))
      ORDER BY id
    )                                                             AS dedupe_index
  FROM files f
) ranked
ON CONFLICT (slug) DO NOTHING;

-- 3c. Bulletins → documents kind=article. Slug = 'bulletin-' || id (unique by
-- construction).
INSERT INTO documents (slug, title, kind, body, frontmatter,
                       tags, author_id, visibility, status,
                       created_at, updated_at)
SELECT
  'bulletin-' || id                                  AS slug,
  title,
  'article',
  body,
  jsonb_build_object('pinned', pinned)               AS frontmatter,
  ARRAY[]::text[],
  (SELECT id FROM users WHERE is_sysop = true ORDER BY id LIMIT 1)                AS author_id,
  'public',
  'published',
  posted_at, posted_at
FROM bulletins
ON CONFLICT (slug) DO NOTHING;
