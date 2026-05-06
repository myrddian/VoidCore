-- =============================================================================
-- V11 — Typed schemas, revision counter, soft delete, full-snapshot revisions.
--
-- The migration plan originally described this as V10, but this codebase now
-- already contains V10__bulletin_sort_order.sql, so the typed-substrate
-- migration lands here as V11.
--
-- Promotes V6's fixed-enum kinds to data-driven schemas, adds a monotonic
-- revision counter and soft-delete columns to documents, and expands
-- document_revisions to a full snapshot of the row at the moment of save
-- (including a flag for deletion events).
--
-- Frontmatter validation against schemas.definition (JSON Schema) happens at
-- the application layer in the follow-up plumbing phase.
-- =============================================================================

-- 1. schemas table -----------------------------------------------------------

CREATE TABLE schemas (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug         TEXT NOT NULL,
  version      INTEGER NOT NULL CHECK (version >= 1),
  label        TEXT NOT NULL,
  description  TEXT,
  definition   JSONB NOT NULL,
  presentation JSONB NOT NULL DEFAULT '{}'::jsonb,
  status       TEXT NOT NULL DEFAULT 'active'
                 CHECK (status IN ('draft', 'active', 'deprecated')),
  created_by   BIGINT REFERENCES users(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (slug, version)
);

CREATE INDEX schemas_slug_active
  ON schemas (slug, version DESC)
  WHERE status = 'active';

-- 2. built-in schemas --------------------------------------------------------

INSERT INTO schemas (slug, version, label, description, definition, presentation, status)
VALUES
  ('note', 1, 'Note', 'A short personal observation, link, or fragment.',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"summary":{"type":"string","maxLength":500},"anchor_doc_id":{"type":"string","format":"uuid"}},"additionalProperties":false}'::jsonb,
    '{"list_columns":["title","author","updated_at"],"sort_default":"-updated_at"}'::jsonb,
    'active'),
  ('article', 1, 'Article', 'Long-form writing — opinion, essay, announcement.',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"summary":{"type":"string","maxLength":500},"pinned":{"type":"boolean","default":false}},"additionalProperties":false}'::jsonb,
    '{"list_columns":["title","author","updated_at"],"sort_default":"-updated_at","sort_pinned_first":true}'::jsonb,
    'active'),
  ('howto', 1, 'How-To', 'Procedural write-up — steps, prerequisites, expected outcome.',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"summary":{"type":"string","maxLength":500},"prerequisites":{"type":"array","items":{"type":"string"}},"outcome":{"type":"string","maxLength":500}},"additionalProperties":false}'::jsonb,
    '{"list_columns":["title","author","updated_at"],"sort_default":"-updated_at"}'::jsonb,
    'active'),
  ('link', 1, 'Link', 'A pointer to an external resource with curated context.',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","required":["url"],"properties":{"url":{"type":"string","format":"uri"},"summary":{"type":"string","maxLength":500},"source_kind":{"type":"string","maxLength":40}},"additionalProperties":false}'::jsonb,
    '{"list_columns":["title","source_kind","updated_at"],"sort_default":"-updated_at"}'::jsonb,
    'active'),
  ('glossary', 1, 'Glossary entry', 'A defined term with optional cross-references.',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","required":["term"],"properties":{"term":{"type":"string","maxLength":120},"see_also":{"type":"array","items":{"type":"string"}}},"additionalProperties":false}'::jsonb,
    '{"list_columns":["title","term","updated_at"],"sort_default":"title"}'::jsonb,
    'active'),
  ('release', 1, 'Release', 'A published work — track, album, EP — with rich metadata.',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","required":["artist"],"properties":{"filename":{"type":"string"},"artist":{"type":"string","maxLength":120},"year":{"type":"integer","minimum":1900,"maximum":2100},"label":{"type":"string","maxLength":120},"catalog_number":{"type":"string","maxLength":60},"genre":{"type":"string","maxLength":60},"external_url":{"type":"string","format":"uri"},"size_bytes":{"type":"integer","minimum":0},"download_count":{"type":"integer","minimum":0}},"additionalProperties":false}'::jsonb,
    '{"list_columns":["title","artist","year","label"],"sort_default":"-year","facets":["genre","year","label"]}'::jsonb,
    'active');

-- 3. documents — rename kind, add version/rev/delete columns -----------------

ALTER TABLE documents
  DROP CONSTRAINT documents_kind_check;

ALTER TABLE documents
  RENAME COLUMN kind TO type_slug;

DROP INDEX IF EXISTS documents_kind;
CREATE INDEX documents_type_slug ON documents (type_slug);

ALTER TABLE documents
  ADD COLUMN type_version INTEGER NOT NULL DEFAULT 1 CHECK (type_version >= 1),
  ADD COLUMN rev          INTEGER NOT NULL DEFAULT 1 CHECK (rev >= 1),
  ADD COLUMN deleted_at   TIMESTAMPTZ,
  ADD COLUMN deleted_by   BIGINT REFERENCES users(id);

ALTER TABLE documents
  ADD CONSTRAINT documents_type_fk
    FOREIGN KEY (type_slug, type_version)
    REFERENCES schemas (slug, version);

CREATE INDEX documents_live
  ON documents (type_slug)
  WHERE deleted_at IS NULL;

CREATE INDEX documents_deleted
  ON documents (deleted_at)
  WHERE deleted_at IS NOT NULL;

-- 4. document_revisions — expand to full snapshot ----------------------------

ALTER TABLE document_revisions
  ADD COLUMN rev          INTEGER,
  ADD COLUMN title        TEXT,
  ADD COLUMN tags         TEXT[],
  ADD COLUMN visibility   TEXT,
  ADD COLUMN status       TEXT,
  ADD COLUMN type_slug    TEXT,
  ADD COLUMN type_version INTEGER,
  ADD COLUMN edit_summary TEXT,
  ADD COLUMN is_deletion  BOOLEAN NOT NULL DEFAULT false;

WITH numbered AS (
  SELECT id,
         ROW_NUMBER() OVER (
           PARTITION BY document_id
           ORDER BY edited_at, id
         ) AS rn
    FROM document_revisions
)
UPDATE document_revisions dr
   SET rev = numbered.rn
  FROM numbered
 WHERE dr.id = numbered.id;

UPDATE document_revisions dr
   SET title        = d.title,
       tags         = d.tags,
       visibility   = d.visibility,
       status       = d.status,
       type_slug    = d.type_slug,
       type_version = d.type_version
  FROM documents d
 WHERE dr.document_id = d.id;

ALTER TABLE document_revisions
  ALTER COLUMN rev          SET NOT NULL,
  ALTER COLUMN title        SET NOT NULL,
  ALTER COLUMN tags         SET NOT NULL,
  ALTER COLUMN visibility   SET NOT NULL,
  ALTER COLUMN status       SET NOT NULL,
  ALTER COLUMN type_slug    SET NOT NULL,
  ALTER COLUMN type_version SET NOT NULL;

ALTER TABLE document_revisions
  ADD CONSTRAINT document_revisions_doc_rev_unique
    UNIQUE (document_id, rev);

ALTER TABLE document_revisions
  ADD CONSTRAINT document_revisions_type_fk
    FOREIGN KEY (type_slug, type_version)
    REFERENCES schemas (slug, version);

DROP INDEX IF EXISTS document_revisions_doc;
CREATE INDEX document_revisions_doc
  ON document_revisions (document_id, rev DESC);

CREATE INDEX document_revisions_deletions
  ON document_revisions (document_id)
  WHERE is_deletion = true;

-- 5. documents.rev backfill --------------------------------------------------

UPDATE documents d
   SET rev = COALESCE(
     (SELECT COUNT(*)
        FROM document_revisions dr
       WHERE dr.document_id = d.id),
     0
   ) + 1;
